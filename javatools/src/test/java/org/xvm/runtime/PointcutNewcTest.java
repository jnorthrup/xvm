package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: NEWC pointcut (0x40-0x43) — new array with type check.
 *
 * Wireproto: opcode byte = 0x40-0x43, kind = ALLOC.
 */
public class PointcutNewcTest {

    @org.junit.jupiter.api.Test
    public void newc0x40_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x40), "NEWC_0(0x40) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void newc0x43_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x43), "NEWC_T(0x43) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void newcAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x40), "NEWC_0(0x40) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void newcKindIsAlloc() {
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x40));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x41));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x42));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x43));
    }

    @org.junit.jupiter.api.Test
    public void publishNewc0x40_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x40, "org/example/MyClass.newArray", 400);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x40, evts[0].opcode);
            assertEquals("NEWC_0", evts[0].opcodeName());
            assertEquals("org/example/MyClass.newArray", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishNewcAllVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x40, "NC0.<alloc>", 1);
            VmPointcutPublisher.publish(0x41, "NC1.<alloc>", 2);
            VmPointcutPublisher.publish(0x42, "NCN.<alloc>", 3);
            VmPointcutPublisher.publish(0x43, "NCT.<alloc>", 4);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(4, evts.length);
            assertEquals(0x40, evts[0].opcode);
            assertEquals(0x41, evts[1].opcode);
            assertEquals(0x42, evts[2].opcode);
            assertEquals(0x43, evts[3].opcode);
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