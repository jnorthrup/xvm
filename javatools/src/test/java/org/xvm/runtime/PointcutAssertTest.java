package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: ASSERT pointcut (0x90-0x92) — assertion.
 * Kind = ASSERT, before=true, after=false.
 */
public class PointcutAssertTest {

    @org.junit.jupiter.api.Test
    public void assert0x90_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x90), "ASSERT(0x90) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void assert0x92_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x92), "ASSERT_V(0x92) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void assertKindIsAssert() {
        assertEquals(VmPointcutDispatch.Kind.ASSERT, VmPointcutDispatch.kindOfStatic(0x90));
        assertEquals(VmPointcutDispatch.Kind.ASSERT, VmPointcutDispatch.kindOfStatic(0x91));
        assertEquals(VmPointcutDispatch.Kind.ASSERT, VmPointcutDispatch.kindOfStatic(0x92));
    }

    @org.junit.jupiter.api.Test
    public void assertNoAfterFlag() {
        assertFalse(VmPointcutDispatch.hasAfterStatic(0x90), "ASSERT(0x90) should NOT fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void publishAssert0x90_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x90, "org/example/MyClass.assert", 900);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x90, evts[0].opcode);
            assertEquals("ASSERT", evts[0].opcodeName());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishAllAssertVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x90, "Assert.assert", 1);
            VmPointcutPublisher.publish(0x91, "Assert.assertM", 2);
            VmPointcutPublisher.publish(0x92, "Assert.assertV", 3);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(3, evts.length);
            assertEquals(0x90, evts[0].opcode);
            assertEquals(0x91, evts[1].opcode);
            assertEquals(0x92, evts[2].opcode);
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