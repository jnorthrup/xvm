package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: NVOK pointcut (0x20-0x2F) — virtual invoke.
 * 4 args × 4 types × 4 variants = 16 opcodes.
 * Kind = CALL, before+after both true.
 */
public class PointcutNvokTest {

    @org.junit.jupiter.api.Test
    public void nvok0x20_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x20), "NVOK_00(0x20) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void nvok0x2F_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x2F), "NVOK_TT(0x2F) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void nvokAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x20), "NVOK_00(0x20) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void nvokKindIsCall() {
        for (int op = 0x20; op <= 0x2F; op++) {
            assertEquals(VmPointcutDispatch.Kind.CALL, VmPointcutDispatch.kindOfStatic(op),
                opcodeName(op) + "(0x" + Integer.toHexString(op) + ") should be CALL");
        }
    }

    @org.junit.jupiter.api.Test
    public void publishNvok0x20_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x20, "org/example/MyClass.virtualMethod", 200);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x20, evts[0].opcode);
            assertEquals("NVOK_00", evts[0].opcodeName());
            assertEquals("org/example/MyClass.virtualMethod", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishNvokAll16Variants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            for (int op = 0x20; op <= 0x2F; op++) {
                VmPointcutPublisher.publish(op, "Nvok.variant", op);
            }

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(16, evts.length);
            for (int i = 0; i < 16; i++) {
                assertEquals(0x20 + i, evts[i].opcode);
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
            "NVOK_00","NVOK_01","NVOK_0N","NVOK_0T",
            "NVOK_10","NVOK_11","NVOK_1N","NVOK_1T",
            "NVOK_N0","NVOK_N1","NVOK_NN","NVOK_NT",
            "NVOK_T0","NVOK_T1","NVOK_TN","NVOK_TT"
        };
        return names[op - 0x20];
    }
}