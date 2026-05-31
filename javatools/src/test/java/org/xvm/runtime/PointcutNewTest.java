package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: NEW pointcut (0x38-0x3B).
 *
 * Wireproto: opcode byte = 0x38-0x3B, kind = ALLOC.
 */
public class PointcutNewTest {

    @org.junit.jupiter.api.Test
    public void new0x38_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x38), "NEW_0(0x38) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void new0x3B_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x3B), "NEW_T(0x3B) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void newAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x38), "NEW_0(0x38) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void newKindIsAlloc() {
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x38));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x39));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x3A));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x3B));
    }

    @org.junit.jupiter.api.Test
    public void publishNew0x38_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "org/example/MyClass.<alloc>", 300);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x38, evts[0].opcode);
            assertEquals("NEW_0", evts[0].opcodeName());
            assertEquals("org/example/MyClass.<alloc>", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishNewAllVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "N0.<alloc>", 1);
            VmPointcutPublisher.publish(0x39, "N1.<alloc>", 2);
            VmPointcutPublisher.publish(0x3A, "NN.<alloc>", 3);
            VmPointcutPublisher.publish(0x3B, "NT.<alloc>", 4);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(4, evts.length);
            assertEquals(0x38, evts[0].opcode);
            assertEquals(0x39, evts[1].opcode);
            assertEquals(0x3A, evts[2].opcode);
            assertEquals(0x3B, evts[3].opcode);
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