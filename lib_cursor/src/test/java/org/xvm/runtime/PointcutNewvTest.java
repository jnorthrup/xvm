package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: NEWV pointcut (0x48-0x4B) — new varargs.
 *
 * Wireproto: opcode byte = 0x48-0x4B, kind = ALLOC.
 */
public class PointcutNewvTest {

    @org.junit.jupiter.api.Test
    public void newv0x48_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x48), "NEWV_0(0x48) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void newv0x4B_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x4B), "NEWV_T(0x4B) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void newvAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x48), "NEWV_0(0x48) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void newvKindIsAlloc() {
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x48));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x49));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x4A));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x4B));
    }

    @org.junit.jupiter.api.Test
    public void publishNewv0x48_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x48, "org/example/MyClass.newVarargs", 480);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x48, evts[0].opcode);
            assertEquals("NEWV_0", evts[0].opcodeName());
            assertEquals("org/example/MyClass.newVarargs", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishNewvAllVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x48, "NV0.<alloc>", 1);
            VmPointcutPublisher.publish(0x49, "NV1.<alloc>", 2);
            VmPointcutPublisher.publish(0x4A, "NVN.<alloc>", 3);
            VmPointcutPublisher.publish(0x4B, "NVT.<alloc>", 4);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(4, evts.length);
            assertEquals(0x48, evts[0].opcode);
            assertEquals(0x49, evts[1].opcode);
            assertEquals(0x4A, evts[2].opcode);
            assertEquals(0x4B, evts[3].opcode);
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