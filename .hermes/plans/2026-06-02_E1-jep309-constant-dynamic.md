# E1 — JEP 309 CONSTANT_Dynamic: Preserve typedef identity through ConstantPool registration

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Stop erasing `TypedefConstant` identity at `ConstantPool.register()` so that lib_cursor pointcut infrastructure doesn't need ~60 LOC of synchronized HashMap bookkeeping (TypedefTable + InternPool) to reconstruct what the runtime threw away.

**Architecture:** Change `ConstantPool.register()` to skip `resolveTypedefs()` when the constant IS a `TypedefConstant` or a type that directly wraps one. The typedef pool index survives past registration. `TypedefConstant.getReferredToType()` still resolves on demand (lazy). `Register` constructors no longer need to call `resolveTypedefs()` because the type is already correct at the pool level. The `TypedefResolutionPublisher` WAL keeps recording — but now `typedefIdx` in the wireproto maps to runtime-visible identity instead of write-only metadata.

**Tech Stack:** Java 21, JUnit 5, existing xvm build (Gradle)

**Current state:** 131 lib_cursor tests green, 28 javatools pointcut tests green. All pass with `./gradlew javatools:test`.

---

## Scope analysis

### The debt being removed

`ConstantPool.register()` at line 177 calls `constant.resolveTypedefs()` unconditionally on every constant. For `TypedefConstant`, this unwraps the typedef to its underlying type — the typedef identity (pool index, name, structure) is lost. Every downstream consumer sees only the resolved type.

To recover typedef identity for pointcut events, lib_cursor would need:
- `TypedefTable`: synchronized `HashMap<Integer, TypedefConstant>` mapping pool index → typedef (~30 LOC)
- `InternPool`: synchronized `HashMap<String, Integer>` for name dedup (~30 LOC)
- Both rebuilt from scratch every JVM launch (E6 warmup debt)

E1 eliminates this by NOT erasing at registration.

### Files that call resolveTypedefs() (42 files)

Only 3 are in the E1 change scope:
1. `ConstantPool.java:177` — skip for TypedefConstant
2. `Register.java:31` — remove call
3. `Register.java:60` — remove call

All other 39 call sites are either:
- Type constant `resolveTypedefs()` overrides (recursive resolution, must stay)
- Compiler AST nodes (compile-time, not runtime identity)
- `TypedefConstant.getReferredToType():93` — lazy accessor, must stay

### NOT in scope

- JEP 309 JVM bytecode emission (XVM has its own constant pool format)
- E4 (Hidden Classes), E6 (Leyden AOT) — depend on E1
- Wireproto format changes (pad byte → typedefIdx) — separate change after E1
- Any lib_cursor changes

---

## Tasks

### Task 1: Failing test — TypedefConstant survives ConstantPool.register()

**Objective:** Write a test proving that after E1, a `TypedefConstant` registered in the pool retains its identity (not unwrapped to the underlying type).

**Files:**
- Create: `javatools/src/test/java/org/xvm/asm/constants/TypedefConstantRegistrationTest.java`

**Step 1: Write failing test**

```java
package org.xvm.asm.constants;

import org.xvm.asm.ConstantPool;
import org.xvm.asm.constants.TypedefConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E1: TypedefConstant identity must survive ConstantPool.register().
 * Before E1: register() calls resolveTypedefs(), which unwraps TypedefConstant
 *             to the underlying TypeConstant — typedef identity is lost.
 * After E1:  register() skips resolveTypedefs() for TypedefConstant,
 *             preserving pool index and identity.
 */
public class TypedefConstantRegistrationTest {

    @Test
    void typedefSurvivesRegistration() {
        // This test needs a ConstantPool with a TypedefConstant.
        // Use the pool's file structure to create a minimal typedef scenario.
        // The test should:
        // 1. Create a ConstantPool
        // 2. Create or obtain a TypedefConstant
        // 3. Register it
        // 4. Assert the registered constant IS a TypedefConstant (not unwrapped)
        //
        // NOTE: Implementation depends on how TypedefConstants are constructed.
        //       They reference a TypedefStructure, which requires a module context.
        //       May need to compile a minimal .x source with a typedef, then inspect
        //       the resulting constant pool.
        //
        // For now, test the negative case: current behavior UNWRAPS typedefs.
        // After E1, this test will pass (typedef survives).
        fail("E1 not yet implemented — test skeleton");
    }
}
```

**Step 2: Run test to verify failure**

Run: `./gradlew javatools:test --tests "TypedefConstantRegistrationTest" 2>&1 | tail -5`
Expected: FAIL

**Step 3: This is a skeleton — flesh out in Task 2 after understanding TypedefConstant construction**

The test needs real TypedefConstants. These come from compiling `.x` sources. The existing test `TypedefCascadeDagReificationTest` compiles a minimal module. We can follow that pattern.

---

### Task 2: Understand TypedefConstant construction for test setup

**Objective:** Read how existing tests create/obtain TypedefConstants. Determine the minimal test fixture.

**Files:**
- Read: `javatools/src/test/java/org/xvm/runtime/TypedefCascadeDagReificationTest.java`
- Read: `javatools/src/main/java/org/xvm/asm/constants/TypedefConstant.java` (full)
- Read: `javatools/src/main/java/org/xvm/asm/constants/TerminalTypeConstant.java:520-530` (the Format.Typedef → getReferredToType hub)

**Step 1: Read TypedefConstant fully**

```bash
cat javatools/src/main/java/org/xvm/asm/constants/TypedefConstant.java
```

**Step 2: Search for existing test patterns that compile .x and inspect constants**

```bash
grep -rn "TypedefConstant" javatools/src/test/
```

**Step 3: Design test fixture**

TypedefConstant wraps a ModuleStructure → TypedefStructure. To create one:
- Option A: Compile a `.x` source with `typedef`, load the `.xtc`, iterate constants
- Option B: Use `ConstantPool` directly with manual structure setup
- Option C: The existing TypedefCascadeDagReificationTest already compiles a module with typedefs via stdlib — extend that pattern

**Preferred:** Option A/C — compile a minimal module, then inspect the pool.

---

### Task 3: Write concrete failing test

**Objective:** Write a test that compiles a `.x` source containing a typedef, then asserts the registered constant in the pool IS a `TypedefConstant`.

**Files:**
- Modify: `javatools/src/test/java/org/xvm/asm/constants/TypedefConstantRegistrationTest.java`

**Step 1: Write the concrete test**

Follow the `TypedefCascadeDagReificationTest` pattern:
1. Write a `.x` source with a typedef to a known type
2. Compile it with stdlib on module path
3. Load the resulting `.xtc`
4. Get the ConstantPool from the FileStructure
5. Iterate constants, find one that was a `TypedefConstant` before registration
6. Assert it's still a `TypedefConstant` (not unwrapped)

The assertion: `assertInstanceOf(TypedefConstant.class, constant)`

Currently this FAILS because `register()` unwraps it. After E1, it PASSES.

**Step 2: Run test**

Run: `./gradlew javatools:test --tests "TypedefConstantRegistrationTest" 2>&1 | tail -10`
Expected: FAIL — constant is unwrapped (is TerminalTypeConstant or similar, not TypedefConstant)

---

### Task 4: Implement — ConstantPool.register skips resolveTypedefs for TypedefConstant

**Objective:** Change `ConstantPool.register()` to preserve `TypedefConstant` identity.

**Files:**
- Modify: `javatools/src/main/java/org/xvm/asm/ConstantPool.java:165-177`

**Step 1: Implement the guard**

Current code (line 177):
```java
constant = (T) constant.resolveTypedefs();
```

New code:
```java
if (!(constant instanceof org.xvm.asm.constants.TypedefConstant)) {
    constant = (T) constant.resolveTypedefs();
}
```

This is the minimal change. TypedefConstants pass through without unwrapping. All other constants resolve as before.

**Step 2: Run the failing test**

Run: `./gradlew javatools:test --tests "TypedefConstantRegistrationTest" 2>&1 | tail -10`
Expected: PASS — TypedefConstant survives registration

**Step 3: Run ALL javatools tests to check for regressions**

Run: `./gradlew javatools:test 2>&1 | tail -20`
Expected: 28 pointcut tests + all existing tests still green

---

### Task 5: Remove resolveTypedefs() from Register constructors

**Objective:** Register constructors no longer need to resolve typedefs because the type is already correct at the pool level.

**Files:**
- Modify: `javatools/src/main/java/org/xvm/asm/Register.java:27-32,49-61`

**Step 1: Remove line 31**

Current:
```java
public Register(TypeConstant type, String sName, MethodStructure method) {
    if (type == null) {
        throw new IllegalArgumentException("type required");
    } else {
        type = type.resolveTypedefs();
    }
```

New:
```java
public Register(TypeConstant type, String sName, MethodStructure method) {
    if (type == null) {
        throw new IllegalArgumentException("type required");
    }
```

**Step 2: Remove line 60**

Current:
```java
public Register(TypeConstant type, String sName, int iArg) {
    if (type == null) {
        switch (iArg) {
        case Op.A_DEFAULT:
        case Op.A_IGNORE:
        case Op.A_IGNORE_ASYNC:
            break;
        default:
            throw new IllegalArgumentException("type required");
        }
    } else {
        type = type.resolveTypedefs();
    }
```

New:
```java
public Register(TypeConstant type, String sName, int iArg) {
    if (type == null) {
        switch (iArg) {
        case Op.A_DEFAULT:
        case Op.A_IGNORE:
        case Op.A_IGNORE_ASYNC:
            break;
        default:
            throw new IllegalArgumentException("type required");
        }
    }
```

**Step 3: Run all tests**

Run: `./gradlew javatools:test 2>&1 | tail -20`
Expected: All tests green

---

### Task 6: Verify full build (javatools only, no clean)

**Objective:** Full javatools build passes with the changes.

**Step 1: Build javatools**

Run: `./gradlew javatools:build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 2: Run javatools tests with --rerun-tasks**

Run: `./gradlew javatools:test --rerun-tasks 2>&1 | tail -20`
Expected: All tests green, no cache masking

---

### Task 7: Verify lib_cursor tests still green

**Objective:** lib_cursor tests pass with javatools changes. Build order: javatools first, lib_cursor last.

**Step 1: Build lib_cursor**

Run: `./gradlew lib_cursor:build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 2: Run lib_cursor tests**

Run: `./gradlew lib_cursor:test --rerun-tasks 2>&1 | tail -20`
Expected: 131 tests green

---

### Task 8: Update TODO.md — mark E1 as delivered

**Objective:** Update the TODO to reflect E1 completion.

**Files:**
- Modify: `lib_cursor/TODO.md`

**Step 1: Move E1 from "Not started" section to "Delivered" section**

Add under Delivered:
```markdown
### E1 — JEP 309 CONSTANT_Dynamic typedef identity preservation
- ConstantPool.register() skips resolveTypedefs() for TypedefConstant (1-line guard)
- Register constructors (lines 31, 60) no longer call resolveTypedefs()
- TypedefConstant.getReferredToType() still resolves lazily on demand
- TypedefTable + InternPool (~60 LOC) no longer needed — typedef pool index survives registration
- Unblocks E4 (Hidden Classes) and E6 (Leyden AOT)
```

Remove E1 from "Not started" section.

---

## Risks and tradeoffs

1. **Register constructor callers may pass pre-resolved types.** If any code constructs a `Register` with a type that WAS a TypedefConstant but was already resolved upstream, removing the `resolveTypedefs()` call is safe — it's already resolved. But if code passes an unresolved TypedefConstant directly to Register, the type will now BE a TypedefConstant instead of the underlying type. This is the INTENDED behavior change — typedef identity preserved.

2. **Type comparison semantics.** Code that does `type instanceof TerminalTypeConstant` where `type` was previously resolved from a typedef will now see `TypedefConstant` instead. This could break:
   - `Frame.checkType()` — type checking at runtime. Needs to handle TypedefConstant.
   - `ObjectHandle.m_clazz` — class composition from type.
   These are E4 concerns (Hidden Classes). For E1, the immediate risk is low because `TypedefConstant.getReferredToType()` still provides the resolved type on demand.

3. **The guard is instanceof-based.** If a type WRAPS a TypedefConstant (e.g., `ParameterizedTypeConstant<TypedefConstant>`), `resolveTypedefs()` will still unwrap the inner typedef. Only bare TypedefConstants are preserved. This is correct for E1 — the typedef pool entry itself is preserved. Nested typedefs in parameterized types resolve through the existing `ParameterizedTypeConstant.resolveTypedefs()` which recursively resolves params.

4. **Test design complexity.** Creating TypedefConstants requires compiling `.x` sources. The test fixture follows the existing TypedefCascadeDagReificationTest pattern (compile minimal module with stdlib on module path).

---

## Verification checklist

- [ ] TypedefConstantRegistrationTest passes (typedef survives registration)
- [ ] `./gradlew javatools:test --rerun-tasks` — all green
- [ ] `./gradlew lib_cursor:test --rerun-tasks` — 131 green
- [ ] No new synchronized HashMap bookkeeping needed
- [ ] TypedefResolutionPublisher WAL still records all 73+ call sites
- [ ] Register constructors simpler (2 lines removed)
