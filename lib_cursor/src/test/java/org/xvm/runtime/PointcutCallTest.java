package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: CALL pointcut (0x10-0x1F) — direct call site.
 * 4 args × 4 types × 4 variants = 16 opcodes.
 * Kind = CALL, before+after both true.
 */
public class PointcutCallTest {

    @org.junit.jupiter.api.Test
    public void call0x10_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x10), "CALL_00(0x10) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void call0x1F_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x1F), "CALL_TT(0x1F) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void callAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x10), "CALL_00(0x10) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void callKindIsCall() {
        for (int op = 0x10; op <= 0x1F; op++) {
            assertEquals(VmPointcutDispatch.Kind.CALL, VmPointcutDispatch.kindOfStatic(op),
                opcodeName(op) + "(0x" + Integer.toHexString(op) + ") should be CALL");
        }
    }

    @org.junit.jupiter.api.Test
    public void publishCall0x10_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x10, "org/example/MyClass.method", 100);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x10, evts[0].opcode);
            assertEquals("CALL_00", evts[0].opcodeName());
            assertEquals("org/example/MyClass.method", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishCallAll16Variants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            for (int op = 0x10; op <= 0x1F; op++) {
                VmPointcutPublisher.publish(op, "Call.variant", op);
            }

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(16, evts.length);
            for (int i = 0; i < 16; i++) {
                assertEquals(0x10 + i, evts[i].opcode);
            }
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    private VmPointcutPublisher.PointcutEvent[] drainAll() {
        java.util.ArrayList<VmPointcutPublisher.PointcutEvent> list = new java.util.ArrayList<>();
        VmPointcutPublisher.drain(list::add);
        return list.toArray(new VmPointcutPublisher.PointcutEvent[0]);
    }

    private static String opcodeName(int op) {
        String[] names = {
            "CALL_00","CALL_01","CALL_0N","CALL_0T",
            "CALL_10","CALL_11","CALL_1N","CALL_1T",
            "CALL_N0","CALL_N1","CALL_NN","CALL_NT",
            "CALL_T0","CALL_T1","CALL_TN","CALL_TT"
        };
        return names[op - 0x10];
    }
}