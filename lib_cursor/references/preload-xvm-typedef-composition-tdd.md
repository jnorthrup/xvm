# PRELOAD xvm typedef composition TDD

Goal
- Draft a TrikeShed-style PRELOAD.md adapted to xvm typedef, mixin composition, and signature-preserving alias composition.
- Keep native xvm `typedef` syntax as the surface form.
- Treat "RFC1 typealias conformance" as a conformance profile over existing typedef syntax/signatures, not as a parser fork introducing a second alias keyword.
- Add an explicit regression tripwire around non-zero type parameters so the first broken site fails loudly.

Ground truth from current xvm
- `TypedefStatement` only emits `typedef <type> <alias>;` into the compiler AST; there is no `typealias` syntax in xvm today.
- `TypedefStructure` stores one `TypeConstant m_type`; typedef is a naming wrapper, not a second runtime shape.
- `TypedefConstant.getReferredToType()` resolves recursively and returns `typeReferred.resolveTypedefs()`; current behavior erases the alias layer at resolution time.
- `TerminalTypeConstant.resolveTypedefs()` collapses `Format.Typedef` to the referred type; this is the main zero-cost point today, but it also destroys alias identity.
- `TypeConstant.combine/union/andNot` call `resolveTypedefs()` first, so typedef composition currently normalizes away before relation algebra runs.
- Existing verifier hooks already fail on bad parameter counts: `VERIFY-02` for unexpected params, `VERIFY-03` for wrong arity.
- Existing callsite instrumentation already tags the typedef-resolution hot spots through `TypedefResolutionPublisher.TypedefCallsite`.

What this draft changes semantically
1. Preserve xvm typedef syntax.
2. Add an RFC1 conformance interpretation:
   - typedef can stand in for a TrikeShed typealias contract
   - method signatures remain written in xvm terms
   - alias conformance is checked by typedef-bearing signatures, not alternate syntax
3. Split alias behavior into two layers:
   - alias surface: name/signature/mixin intent survives for tooling and dispatch planning
   - carrier body: current zero-cost `resolveTypedefs()` collapse still supplies runtime storage shape
4. Introduce a params tripwire:
   - if alias composition stops working when `params != 0`, the tests fail first at verifier / relation / invocation boundaries
5. Define "duck-type-able with a new vtable by dispatch" as:
   - same callable/property surface across DSL steps
   - dispatch surface can widen/narrow through mixin composition
   - carrier object may remain storage-compatible while dispatch metadata changes

TDD scope
- Production targets later:
  - `javatools/src/main/java/org/xvm/asm/constants/TypedefConstant.java`
  - `javatools/src/main/java/org/xvm/asm/constants/TerminalTypeConstant.java`
  - `javatools/src/main/java/org/xvm/asm/constants/TypeConstant.java`
  - `javatools/src/main/java/org/xvm/compiler/ast/TypedefStatement.java`
  - `lib_cursor/src/main/java/org/xvm/runtime/XvmPrimitiveTranslationTable.java`
- Test targets first:
  - `javatools/src/test/java/org/xvm/runtime/PreloadTypedefConformanceTest.java`
  - `javatools/src/test/java/org/xvm/runtime/PreloadTypedefParamTripwireTest.java`
  - `javatools/src/test/java/org/xvm/runtime/PreloadDslDispatchTest.java`

RED -> GREEN matrix

Test 1: syntax stays xvm-native
Purpose
- Prove RFC1 conformance does not require a new parser keyword.

Write first
```java
@Test
void typedefSyntaxRemainsNativeXvm() throws Exception {
    var source = """
        module AliasSyntax {
            typedef Int UserId;
            typedef List<String> Names;
            void run() {}
        }
        """;
    var result = compile(source);
    assertTrue(result.success(), result.stderr());
}
```

Expected first failure
- None if compile harness is missing; otherwise this is a guard against accidentally introducing `typealias` syntax dependence.

Green condition
- `typedef` remains sufficient for alias-bearing modules.

Test 2: alias signatures survive resolution planning
Purpose
- Prove method signatures can carry typedef names long enough for conformance checks before final carrier collapse.

Write first
```java
@Test
void typedefSignatureCarriesAliasIntentBeforeCollapse() throws Exception {
    var source = """
        module AliasSignature {
            typedef Int UserId;
            interface Api {
                UserId next(UserId prior);
            }
        }
        """;
    var model = compileAndLoad(source);
    assertEquals("UserId", model.firstTypedefName());
    assertEquals("UserId", model.firstMethodParamTypeName());
    assertEquals("UserId", model.firstMethodReturnTypeName());
}
```

Expected first failure
- Current code likely reports only the resolved carrier (`Int`) at some inspection boundary.

Green condition
- Tooling/conformance view can still see alias names even when runtime carrier is zero-cost.

Test 3: params tripwire for non-parameterized typedef
Purpose
- Fail loudly when alias conformance accidentally starts admitting illegal params.

Write first
```java
@Test
void typedefWithUnexpectedParamsFailsVerify02() throws Exception {
    var source = """
        module BadParams {
            typedef Int UserId;
            UserId<String> broken() {throw new Exception();}
        }
        """;
    var result = compile(source);
    assertFalse(result.success());
    assertTrue(result.stderr().contains("VERIFY-02"), result.stderr());
}
```

Green condition
- `params != 0` on a non-parameterized typedef always trips `VERIFY-02`.

Test 4: params tripwire for wrong generic arity
Purpose
- Preserve `VERIFY-03` boundary when typedef composition wraps parameterized carriers.

Write first
```java
@Test
void typedefWrongArityFailsVerify03() throws Exception {
    var source = """
        module BadArity {
            typedef Map<String, Int> Scores;
            Scores<String> broken() {throw new Exception();}
        }
        """;
    var result = compile(source);
    assertFalse(result.success());
    assertTrue(result.stderr().contains("VERIFY-03"), result.stderr());
}
```

Green condition
- parameter count remains enforced after typedef conformance layer is added.

Test 5: mixin composition keeps duck surface
Purpose
- Prove typedef+mixin composition preserves substitutable method surface.

Write first
```java
@Test
void typedefMixinCompositionPreservesSubstitutableSurface() throws Exception {
    var source = """
        module DuckSurface {
            interface Step1 { Step2 step1(); }
            interface Step2 { Step3 step2(); }
            interface Step3 { Done step3(); }
            interface Done  { String value(); }
            typedef Step1 Flow;
            void run() {}
        }
        """;
    var model = compileAndLoad(source);
    assertTrue(model.surface("Flow").containsMethod("step1"));
    assertTrue(model.surface("Step2").containsMethod("step2"));
    assertTrue(model.surface("Step3").containsMethod("step3"));
    assertTrue(model.surface("Done").containsMethod("value"));
}
```

Expected first failure
- current typedef collapse may erase planned dispatch surface too early.

Green condition
- substitutable surface is checked through method signatures / type info, not erased immediately by carrier normalization.

Test 6: DSL chain returns one carrier family, dispatch surface changes by step
Purpose
- Capture the user’s `dsl.step1().step2().step3()` requirement.

Write first
```java
@Test
void dslStepsShareCarrierButAdvanceDispatchSurface() throws Exception {
    var source = """
        module DslFlow {
            interface Step1 { Step2 step1(); }
            interface Step2 { Step3 step2(); }
            interface Step3 { Done step3(); }
            interface Done  { String value(); }
            class FlowImpl implements Step1, Step2, Step3, Done {
                Step2 step1() {return this;}
                Step3 step2() {return this;}
                Done  step3() {return this;}
                String value(){return "ok";}
            }
        }
        """;
    var model = compileAndLoad(source);
    assertEquals("FlowImpl", model.carrierOf("Step1"));
    assertEquals("FlowImpl", model.carrierOf("Step2"));
    assertEquals("FlowImpl", model.carrierOf("Step3"));
    assertEquals("FlowImpl", model.carrierOf("Done"));
    assertNotEquals(model.dispatchShape("Step1"), model.dispatchShape("Done"));
}
```

Green condition
- one storage-compatible carrier can back multiple dispatch surfaces.
- dispatch metadata changes, carrier need not.

Test 7: relation algebra remains typedef-aware at the planning boundary
Purpose
- Catch early collapse in `combine`, `union`, and `andNot`.

Write first
```java
@Test
void typedefPlanningBoundarySurvivesRelationOps() {
    var trace = traceTypedefRelationOps();
    assertTrue(trace.hit("TC_CombineThis"));
    assertTrue(trace.hit("TC_UnionThis"));
    assertTrue(trace.hit("TC_AndNotThis"));
}
```

Green condition
- pointcut trace proves typedef-bearing relation paths still execute through the intended callsites.

Test 8: invocation/lambda/signature callsites stay covered
Purpose
- Lock the hot callsites where alias conformance tends to disappear.

Required hit set
- `IE_ArgType1`
- `IE_ArgType2`
- `IE_FnType1`
- `IE_FnType2`
- `LE_ReqFnType`
- `MDS_Resolve`
- `TCS_PropType`
- `PTC_Param`
- `TTC_Typedef`

Write first
```java
@Test
void preloadConformanceExercisesHotTypedefCallsites() {
    var trace = compileDslFixtureWithTypedefs();
    assertTrue(trace.hit("IE_FnType1"));
    assertTrue(trace.hit("LE_ReqFnType"));
    assertTrue(trace.hit("MDS_Resolve"));
    assertTrue(trace.hit("PTC_Param"));
    assertTrue(trace.hit("TTC_Typedef"));
}
```

Green condition
- pointcut coverage proves the draft is pinned to real typedef-resolution sites, not a synthetic story.

Test 9: vtable intent map stays explicit
Purpose
- Tie the PRELOAD story to existing `VtableLayout` and `VtableOptions` scaffolding.

Write first
```java
@Test
void vtableIntentMapSupportsInlineAndBoxedAliasCarriers() {
    var dec64 = XvmPrimitiveTranslationTable.vtableOptions(XvmPrimitiveTranslationTable.XvmPrimitive.Dec64);
    var nullable = XvmPrimitiveTranslationTable.vtableOptions(XvmPrimitiveTranslationTable.XvmPrimitive.Nullable);
    assertEquals(VtableLayout.INLINE, dec64.layout());
    assertEquals(VtableLayout.BOXED, nullable.layout());
    assertTrue((dec64.mixinCompat() & 0x04) != 0);
}
```

Green condition
- PRELOAD can talk about inline carrier vs boxed alias without inventing a second dispatch model.

Suggested run order
1. `./gradlew :javatools:test --rerun-tasks --tests 'org.xvm.runtime.PreloadTypedefConformanceTest'`
2. `./gradlew :javatools:test --rerun-tasks --tests 'org.xvm.runtime.PreloadTypedefParamTripwireTest'`
3. `./gradlew :javatools:test --rerun-tasks --tests 'org.xvm.runtime.PreloadDslDispatchTest'`
4. `./gradlew :javatools:test --rerun-tasks --tests 'org.xvm.runtime.TypedefCascadeDagReificationTest'`

Draft PRELOAD.md adapted to xvm

```md
below is the xvm-facing preload for TrikeShed algebra under native xvm typedef and mixin composition.

## typedef discipline

- xvm keeps `typedef` as the only alias syntax
- RFC1 conformance is a contract over typedef usage, not a new keyword
- typedef names preserve signature intent even when carrier storage collapses to the referred type
- params matter: when `params != 0`, alias conformance must either resolve exactly or fail loudly
- typealiases compress semantics, not substance; typedef does the same job here under xvm syntax

Read typedef as:
- typedef surface = name, signature, dispatch intent
- referred type = carrier/storage body
- resolveTypedefs = zero-cost collapse point
- conformance = proof that alias surface can survive long enough for checking before collapse

## mixin composition

- mixins widen dispatch surface without requiring a distinct storage carrier
- vtable intent is explicit: VIRTUAL, INTERFACE, INLINE, BOXED
- inline carriers are preferred when the body is scalar and stable
- boxed carriers are fallback when nullability or wrapper semantics must survive
- mixin compatibility is a bitmask, not an informal convention

Read mixin composition as:
- field access = 0x01
- method forwarding = 0x02
- type unification = 0x04
- type intersection = 0x08

## typedef composition

- typedef may name a primitive, parameterized type, union, intersection, or function signature
- composition must preserve callable shape across relation algebra (`combine`, `union`, `andNot`)
- alias chains are acceptable if they terminate and retain verifier correctness
- recursive self-reference is not a feature; parser/name-resolution must stop it before runtime collapse

## DSL transitions

The desired DSL shape is:

`dsl.step1().step2().step3()`

with these invariants:
- one carrier family may back all steps
- each step exposes a refined dispatch surface
- the object remains duck-type-able by the methods visible at that step
- dispatch metadata may change even when storage body does not
- signatures remain typedef-addressable in xvm source

## params tripwire

When something stops working, test params first.

- zero params on non-generic typedef: expected steady state
- non-zero params on non-generic typedef: `VERIFY-02`
- wrong arity on generic typedef: `VERIFY-03`
- wrong constrained param type: verifier/type-constraint failure

This is the first regression boundary because alias composition tends to fail there before it fails anywhere more intelligible.

## callsite bias

Watch these real typedef-resolution sites first:
- `TTC_Typedef`
- `PTC_Param`
- `IE_FnType1`
- `IE_FnType2`
- `LE_ReqFnType`
- `MDS_Resolve`
- `TCS_PropType`

If the pointcuts go dark there, typedef composition stopped being real.
```

Definition of done
- The three new tests exist and fail first.
- The PRELOAD draft is accepted as the semantic contract.
- The params tripwire is explicit and linked to `VERIFY-02` / `VERIFY-03`.
- Pointcut coverage is used as proof for typedef-bearing callsites, not as a decorative appendix.
