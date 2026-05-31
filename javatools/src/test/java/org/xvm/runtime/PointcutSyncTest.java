package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: SYN_INIT (0x33) — synchronization init.
 * Kind = SYNC, before=true, after=false.
 */
public class PointcutSyncTest {

    @org.junit.jupiter.api.Test
    public void synInit0x33_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x33), "SYN_INIT(0x33) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void synInit0x33_noAfterFlag() {
        assertFalse(VmPointcutDispatch.hasAfterStatic(0x33), "SYN_INIT(0x33) should NOT fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void synInitKindIsSync() {
        assertEquals(VmPointcutDispatch.Kind.SYNC, VmPointcutDispatch.kindOfStatic(0x33));
    }

    @org.junit.jupiter.api.Test
    public void publishSynInit_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x33, "org/example/MyClass.<clinit>", 0);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x33, evts[0].opcode);
            assertEquals("SYN_INIT", evts[0].opcodeName());
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