package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: RETURN pointcut (0x4C-0x4F).
 *
 * Wireproto: opcode byte = 0x4C-0x4F, kind = RETURN.
 */
public class PointcutReturnTest {

    @org.junit.jupiter.api.Test
    public void return0x4C_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x4C), "RETURN_0(0x4C) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void return0x4F_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x4F), "RETURN_T(0x4F) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void returnKindIsReturn() {
        assertEquals(VmPointcutDispatch.Kind.RETURN, VmPointcutDispatch.kindOfStatic(0x4C));
        assertEquals(VmPointcutDispatch.Kind.RETURN, VmPointcutDispatch.kindOfStatic(0x4D));
        assertEquals(VmPointcutDispatch.Kind.RETURN, VmPointcutDispatch.kindOfStatic(0x4E));
        assertEquals(VmPointcutDispatch.Kind.RETURN, VmPointcutDispatch.kindOfStatic(0x4F));
    }

    @org.junit.jupiter.api.Test
    public void publishReturn0x4C_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x4C, "org/example/MyClass.method", 500);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x4C, evts[0].opcode);
            assertEquals("RETURN_0", evts[0].opcodeName());
            assertEquals("org/example/MyClass.method", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishReturnAllVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x4C, "R0.return", 1);
            VmPointcutPublisher.publish(0x4D, "R1.return", 2);
            VmPointcutPublisher.publish(0x4E, "RN.return", 3);
            VmPointcutPublisher.publish(0x4F, "RT.return", 4);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(4, evts.length);
            assertEquals(0x4C, evts[0].opcode);
            assertEquals(0x4D, evts[1].opcode);
            assertEquals(0x4E, evts[2].opcode);
            assertEquals(0x4F, evts[3].opcode);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    private VmPointcutPublisher.PointcutEvent[] drainAll() {
        java.util.ArrayList<VmPointcutPublisher.PointcutEvent> list = new java.util.ArrayList<>();
        VmPointcutPublisher.drain(list::add);
        return list.toArray(new VmPointcutPublisher.PointcutEvent[0]);
    }
}