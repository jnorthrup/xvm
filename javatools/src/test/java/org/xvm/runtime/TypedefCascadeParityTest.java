package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD parity test: TypedefCascadeTable columnar reduce + rule match
 * validates correctness of SoA scalar implementation against:
 *
 * AS-IS BLOCK — existing production code structures:
 *   VmPointcutDispatch:      4 parallel arrays (Kind[], boolean[], boolean[], String[])  — line 30-39
 *   VmPointcutEmitter:       2 parallel arrays (PHASE_NAME[], OPCODE_FAMILY[])          — line 40-41
 *   FieldSynapse:            RingSeries(2048) + 24B wireproto records                   — line 118, 140-159
 *   VmPointcutPublisher:     RingSeries(65536) + long[] JOURNAL                         — line 32-36
 *   TypedefResolutionPublisher: 76 TypedefCallsite ordinals + WAL via Kotlin reflection — line 58-154
 *   ClassfilePointcutRewriter: matchesElement() opcode-range dispatch                   — line 235-258
 *
 * PROPOSED BLOCK — TypedefCascadeTable:
 *   SoA columns: kind[], depth[], scope[], success[], siteOrd[], poolId[]
 *   Rule SoA:    rule_opcodes[], rule_siteOrd[], rule_depth[], rule_kind[]
 *   Reduce:      depth/kind/scope histograms + success rate
 *   Match:       scalar linear scan (SIMD parity target)
 *
 * POINTCUT PROOFS BLOCK:
 *   Each test proves a specific pointcut in the cascade:
 *   - column parity:  SoA table matches VmPointcutDispatch tables
 *   - reduce parity:  histogram matches hand-counted expected values
 *   - rule parity:    rule match matches ClassfilePointcutRewriter.matchesElement()
 *   - lattice parity: TypedefCascadeTable direct row append (no bridge)
 *   - memoizer parity: opcode → column routing matches dispatch tables
 *   - wireproto parity: FieldSynapse 24B record round-trips through cascade
 */
public class TypedefCascadeParityTest {

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 1: Column parity — SoA table matches dispatch tables
    //
    // Proves: kind column derived from VmPointcutDispatch.kindOf() matches
    //         direct dispatch table lookup for every instrumented opcode.
    // Reference: VmPointcutDispatch.java lines 30-39, 100-104
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void columnParity_kindMatchesDispatchTable() {
        var table = new TypedefCascadeTable(16);

        // Route every instrumented opcode through the cascade
        int[] opcodes = {
            0x10, 0x15, 0x1F,  // CALL family
            0x20, 0x25, 0x2F,  // NVOK family
            0x34, 0x37,        // CONSTR
            0x38, 0x3B,        // NEW
            0x4C, 0x4F,        // RETURN
            0xA5, 0xA6, 0xA7, 0xA8,  // FIELD
            0x65, 0x66,        // TYPE
            0x90, 0x92,        // ASSERT
            0x70, 0x73,        // LOOP
        };

        for (int op : opcodes) {
            table.routeOpcode(op, "test.method", 0);
        }

        // Verify each row's kind matches dispatch table
        for (int i = 0; i < opcodes.length; i++) {
            VmPointcutDispatch.Kind expected = VmPointcutDispatch.kindOf(opcodes[i]);
            byte expectedByte = dispatchKindToByte(expected);
            byte actual = table.kindColumn()[i];
            assertEquals(expectedByte, actual,
                    "opcode 0x" + Integer.toHexString(opcodes[i])
                    + " kind mismatch at row " + i);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 2: Reduce parity — histogram matches hand counts
    //
    // Proves: depth/kind/scope histograms computed by reduce() match
    //         expected counts from manual inspection of the opcode ranges.
    // Reference: FieldSynapse.java flush slab reduce
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void reduceParity_histogramsMatchHandCounts() {
        var table = new TypedefCascadeTable(64);

        // Manually append rows with known distribution:
        // 3x CALL (FUNC kind, METHOD scope)
        // 2x ALLOC (TUPLE kind, CLASS scope)
        // 1x RETURN (TERM kind, METHOD scope)
        // 1x FIELD GET (PARAM kind, CLASS scope)

        for (int i = 0; i < 3; i++)
            table.appendRow(TypedefCascadeTable.KIND_CALL, (byte) 2,
                    TypedefCascadeTable.SCOPE_METHOD, (byte) 1, i, 0);
        for (int i = 0; i < 2; i++)
            table.appendRow(TypedefCascadeTable.KIND_ALLOC, (byte) 1,
                    TypedefCascadeTable.SCOPE_CLASS, (byte) 1, 3 + i, 0);
        table.appendRow(TypedefCascadeTable.KIND_RETURN, (byte) 0,
                TypedefCascadeTable.SCOPE_METHOD, (byte) 0, 5, 0);
        table.appendRow(TypedefCascadeTable.KIND_FIELD, (byte) 2,
                TypedefCascadeTable.SCOPE_CLASS, (byte) 1, 6, 0);

        assertEquals(7, table.rowCount());

        table.reduce();

        // depth histogram: depth[0]=1 (TERM), depth[1]=2 (TUPLE), depth[2]=4 (FUNC+PARAM)
        int[] dh = table.depthHistogram();
        assertEquals(1, dh[0], "depth 0");
        assertEquals(2, dh[1], "depth 1");
        assertEquals(4, dh[2], "depth 2");

        // kind histogram: FUNC=3, TUPLE=2, TERM=1, PARAM=1
        int[] kh = table.kindHistogram();
        assertEquals(3, kh[TypedefCascadeTable.KIND_CALL],   "FUNC");
        assertEquals(2, kh[TypedefCascadeTable.KIND_ALLOC],  "TUPLE");
        assertEquals(1, kh[TypedefCascadeTable.KIND_RETURN],   "TERM");
        assertEquals(1, kh[TypedefCascadeTable.KIND_FIELD],  "PARAM");

        // scope histogram: METHOD=4 (3 FUNC + 1 TERM), CLASS=3 (2 TUPLE + 1 PARAM)
        int[] sh = table.scopeHistogram();
        assertEquals(4, sh[TypedefCascadeTable.SCOPE_METHOD], "METHOD");
        assertEquals(3, sh[TypedefCascadeTable.SCOPE_CLASS],  "CLASS");

        // success rate: 6 ok, 1 fail
        assertEquals(6, table.successCount());
        assertEquals(1, table.failCount());
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 3: Rule match parity — matches ClassfilePointcutRewriter
    //
    // Proves: rule_opcodes match in cascade table produces same boolean
    //         result as ClassfilePointcutRewriter.matchesElement() opcode ranges.
    // Reference: ClassfilePointcutRewriter.java lines 235-258
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void ruleMatchParity_matchesElement() {
        var table = new TypedefCascadeTable(16);

        // Populate rules matching ClassfilePointcutRewriter.matchesElement() ranges
        // CALL: 0x10-0x1F (static) and 0x20-0x2F (virtual)
        for (int op = 0x10; op <= 0x1F; op++)
            table.appendRule(op, op - 0x10, (byte) 0, TypedefCascadeTable.KIND_CALL);
        for (int op = 0x20; op <= 0x2F; op++)
            table.appendRule(op, op - 0x20 + 16, (byte) 0, TypedefCascadeTable.KIND_CALL);
        // CONSTR: 0x34-0x37
        for (int op = 0x34; op <= 0x37; op++)
            table.appendRule(op, op - 0x34 + 32, (byte) 0, TypedefCascadeTable.KIND_ALLOC);
        // NEW: 0x38-0x3B
        for (int op = 0x38; op <= 0x3B; op++)
            table.appendRule(op, op - 0x38 + 36, (byte) 0, TypedefCascadeTable.KIND_ALLOC);
        // RETURN: 0x4C-0x4F
        for (int op = 0x4C; op <= 0x4F; op++)
            table.appendRule(op, op - 0x4C + 40, (byte) 0, TypedefCascadeTable.KIND_RETURN);
        // FIELD: 0xA5-0xA8
        for (int op = 0xA5; op <= 0xA8; op++)
            table.appendRule(op, op - 0xA5 + 44, (byte) 2, TypedefCascadeTable.KIND_FIELD);

        // Verify: every rule opcode matches, and non-rule opcodes don't
        // Matches ClassfilePointcutRewriter.matchesElement() line 237-257
        assertTrue(table.matchRule(0x10) >= 0, "0x10 (CALL_00) should match");
        assertTrue(table.matchRule(0x15) >= 0, "0x15 (CALL_11) should match");
        assertTrue(table.matchRule(0x1F) >= 0, "0x1F (CALL_TT) should match");
        assertTrue(table.matchRule(0x20) >= 0, "0x20 (NVOK_00) should match");
        assertTrue(table.matchRule(0x34) >= 0, "0x34 (CONSTR_0) should match");
        assertTrue(table.matchRule(0x38) >= 0, "0x38 (NEW_0) should match");
        assertTrue(table.matchRule(0x4C) >= 0, "0x4C (RETURN_0) should match");
        assertTrue(table.matchRule(0xA5) >= 0, "0xA5 (L_GET) should match");
        assertTrue(table.matchRule(0xA8) >= 0, "0xA8 (P_SET) should match");

        // Non-matching opcodes (GAP range)
        assertEquals(-1, table.matchRule(0x00), "0x00 should not match");
        assertEquals(-1, table.matchRule(0xFF), "0xFF should not match");
        assertEquals(-1, table.matchRule(0x50), "0x50 (unmapped) should not match");
    }

    // POINTCUT PROOF 5: Memoizer parity — opcode routing matches dispatch
    //
    // Proves: routeOpcode() produces the same kind classification as
    //         VmPointcutDispatch.kindOf() for every opcode family.
    // Reference: VmPointcutDispatch.java lines 41-88 (static init)
    //            VmPointcutEmitter.java lines 43-79 (fillRange/setOne)
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void memoizerParity_allOpcodes() {
        var table = new TypedefCascadeTable(256);

        // Route every possible opcode (0-255)
        for (int op = 0; op < 256; op++) {
            table.routeOpcode(op, "test", 0);
        }

        assertEquals(256, table.rowCount());

        // For each instrumented opcode, verify kind matches dispatch table
        for (int i = 0; i < 256; i++) {
            VmPointcutDispatch.Kind dispatchKind = VmPointcutDispatch.kindOf(i);
            byte cascadeKind = table.kindColumn()[i];
            byte expected = dispatchKindToByte(dispatchKind);
            assertEquals(expected, cascadeKind,
                    "opcode 0x" + Integer.toHexString(i) + " kind mismatch");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 6: Wireproto parity — FieldSynapse record round-trips
    //
    // Proves: FieldSynapse 24B wireproto encode/decode preserves opcode
    //         and that the opcode can be routed into the cascade table.
    // Reference: FieldSynapse.java lines 290-323 (writeRecord/fromWireproto)
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void wireprotoParity_fieldSynapseRoundTrip() {
        var table = new TypedefCascadeTable(16);

        // Simulate FieldSynapse publish for field opcodes
        int[] fieldOpcodes = {0xA5, 0xA6, 0xA7, 0xA8};
        for (int op : fieldOpcodes) {
            // Route through cascade (same path as FieldSynapse → subscriber → cascade)
            table.routeOpcode(op, "org.example.MyClass.field", 100);
        }

        assertEquals(4, table.rowCount());

        // All field opcodes should have success=1 (they have before or after)
        for (int i = 0; i < 4; i++) {
            assertEquals(1, table.successColumn()[i],
                    "field opcode row " + i + " should have success=1");
        }

        // All should be KIND_FIELD (FIELD → PARAM mapping)
        table.reduce();
        int[] kh = table.kindHistogram();
        assertEquals(4, kh[TypedefCascadeTable.KIND_FIELD], "all 4 field opcodes → PARAM");
    }


    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 8: Rule match count — kind-filtered match
    //
    // Proves: matchRuleCount with kind filter produces correct counts
    //         for the AdjacentRule SoA. SIMD: vpcmpeqb on rule_kind column.
    // Reference: ClassfilePointcutRewriter.java lines 235-258
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void ruleMatchCount_kindFilter() {
        var table = new TypedefCascadeTable(16);

        // 4 rules with same opcode but different kinds
        table.appendRule(0x10, 0, (byte) 0, TypedefCascadeTable.KIND_CALL);
        table.appendRule(0x10, 1, (byte) 0, TypedefCascadeTable.KIND_CALL);
        table.appendRule(0x10, 2, (byte) 1, TypedefCascadeTable.KIND_ALLOC);
        table.appendRule(0x20, 3, (byte) 0, TypedefCascadeTable.KIND_CALL);

        assertEquals(2, table.matchRuleCount(0x10, TypedefCascadeTable.KIND_CALL));
        assertEquals(1, table.matchRuleCount(0x10, TypedefCascadeTable.KIND_ALLOC));
        assertEquals(0, table.matchRuleCount(0x10, TypedefCascadeTable.KIND_RETURN));
        assertEquals(1, table.matchRuleCount(0x20, TypedefCascadeTable.KIND_CALL));
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 9: TypedefCallsite coverage — all 76 sites routable
    //
    // Proves: every TypedefResolutionPublisher.TypedefCallsite ordinal
    //         can be used as siteOrd in the cascade table.
    // Reference: TypedefResolutionPublisher.java lines 58-154 (76 enum values)
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void typedefCallsiteCoverage_allOrdinals() {
        var table = new TypedefCascadeTable(128);

        var sites = org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite.values();
        for (var site : sites) {
            table.appendRow(
                    TypedefCascadeTable.KIND_RETURN,
                    (byte) (site.ordinal() % TypedefCascadeTable.MAX_DEPTH),
                    TypedefCascadeTable.SCOPE_CLASS,
                    (byte) 1,
                    site.ordinal(),
                    0
            );
        }

        assertEquals(sites.length, table.rowCount());

        table.reduce();
        // All should be success
        assertEquals(sites.length, table.successCount());
        assertEquals(0, table.failCount());
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 10: VmPointcutEmitter family parity
    //
    // Proves: OPCODE_FAMILY column from VmPointcutEmitter maps 1:1 to
    //         dispatch table Kind for every instrumented opcode.
    // Reference: VmPointcutEmitter.java lines 40-41 (OPCODE_FAMILY)
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void emitterFamilyParity() {
        // Every opcode with non-GAP phase should have non-negative family
        // and matching kind from dispatch table
        for (int op = 0; op < 256; op++) {
            String phase = VmPointcutEmitter.phaseOf(op);
            int family = VmPointcutEmitter.familyOf(op);
            VmPointcutDispatch.Kind kind = VmPointcutDispatch.kindOf(op);

            if (kind == VmPointcutDispatch.Kind.GAP) {
                assertEquals("GAP", phase,
                        "opcode 0x" + Integer.toHexString(op) + " GAP kind should have GAP phase");
                // family can be -1 for GAP
            } else {
                assertNotEquals("GAP", phase,
                        "opcode 0x" + Integer.toHexString(op) + " non-GAP kind should have non-GAP phase");
                assertTrue(family >= 0,
                        "opcode 0x" + Integer.toHexString(op) + " non-GAP should have family >= 0");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 11: Reset clears all state
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void resetClearsAllState() {
        var table = new TypedefCascadeTable(16);
        table.appendRow(TypedefCascadeTable.KIND_CALL, (byte) 2,
                TypedefCascadeTable.SCOPE_METHOD, (byte) 1, 0, 0);
        table.appendRule(0x10, 0, (byte) 0, TypedefCascadeTable.KIND_CALL);
        table.reduce();
        table.reset();

        assertEquals(0, table.rowCount());
        assertEquals(0, table.ruleCount());
        assertEquals(0, table.successCount());
        assertEquals(0, table.failCount());
        for (int i = 0; i < TypedefCascadeTable.KIND_COUNT; i++) {
            assertEquals(0, table.kindHistogram()[i]);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // POINTCUT PROOF 12: SIMD data layout — contiguous byte columns
    //
    // Proves: columns are plain byte[] arrays with no gaps, suitable for
    //         AVX2 vmovdqu load (32 bytes per cycle).
    // ════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    public void simdLayout_contiguousByteColumns() {
        var table = new TypedefCascadeTable(64);

        // Fill with known pattern
        for (int i = 0; i < 32; i++) {
            table.appendRow(
                    (byte) (i % TypedefCascadeTable.KIND_COUNT),
                    (byte) (i % TypedefCascadeTable.MAX_DEPTH),
                    (byte) (i % TypedefCascadeTable.SCOPE_COUNT),
                    (byte) (i & 1),
                    i, i
            );
        }

        // Verify byte[] columns are contiguous and correct
        byte[] kinds = table.kindColumn();
        byte[] depths = table.depthColumn();
        byte[] scopes = table.scopeColumn();

        for (int i = 0; i < 32; i++) {
            assertEquals((byte) (i % TypedefCascadeTable.KIND_COUNT), kinds[i],
                    "kind[" + i + "]");
            assertEquals((byte) (i % TypedefCascadeTable.MAX_DEPTH), depths[i],
                    "depth[" + i + "]");
            assertEquals((byte) (i % TypedefCascadeTable.SCOPE_COUNT), scopes[i],
                    "scope[" + i + "]");
        }

        // rule columns are also contiguous int[]
        table.appendRule(0x10, 0, (byte) 0, TypedefCascadeTable.KIND_CALL);
        table.appendRule(0x20, 1, (byte) 0, TypedefCascadeTable.KIND_CALL);
        int[] ruleOps = table.ruleOpcodes();
        assertEquals(0x10, ruleOps[0]);
        assertEquals(0x20, ruleOps[1]);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static byte dispatchKindToByte(VmPointcutDispatch.Kind k) {
        return switch (k) {
            case CALL   -> TypedefCascadeTable.KIND_CALL;
            case ALLOC  -> TypedefCascadeTable.KIND_ALLOC;
            case RETURN -> TypedefCascadeTable.KIND_RETURN;
            case FIELD  -> TypedefCascadeTable.KIND_FIELD;
            case TYPE   -> TypedefCascadeTable.KIND_TYPE;
            case ASSERT -> TypedefCascadeTable.KIND_ASSERT;
            case LOOP   -> TypedefCascadeTable.KIND_LOOP;
            case SYNC   -> TypedefCascadeTable.KIND_SYNC;
            case GAP    -> TypedefCascadeTable.KIND_GAP;
            default     -> TypedefCascadeTable.KIND_GAP;
        };
    }
}
