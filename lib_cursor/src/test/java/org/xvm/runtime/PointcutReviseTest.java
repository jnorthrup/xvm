package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * revise() — U (Update) path with old nano journaling.
 * Tests that revise replaces an event and bumps the version stamp.
 */
public class PointcutReviseTest {

    @org.junit.jupiter.api.Test
    public void revise_replacesEventAtIndex() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "Original.methodName()", 100);
            assertEquals(1, VmPointcutPublisher.size());

            VmPointcutPublisher.PointcutEvent original = VmPointcutPublisher.peek(0);
            long originalNano = original.nano;

            // Revised nano will be >= original.
            VmPointcutPublisher.PointcutEvent revised = new VmPointcutPublisher.PointcutEvent(
                    original.seq,
                    originalNano + 1_000_000,
                    0x35,  // changed opcode
                    200,   // changed addr
                    "Revised.methodName()"
            );

            VmPointcutPublisher.revise(0, revised);

            VmPointcutPublisher.PointcutEvent after = VmPointcutPublisher.peek(0);
            assertEquals(0x35, after.opcode);
            assertEquals(200, after.addr);
            assertEquals("Revised.methodName()", after.methodName());
            assertTrue(after.nano >= originalNano);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void revise_bumpsVersionStamp() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x10, "ReviseVer.test", 1);
            long v1 = VmPointcutPublisher.versionStamp();

            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(99, System.nanoTime(), 0x11, 2, "ReviseVer.test2");
            VmPointcutPublisher.revise(0, evt);

            long v2 = VmPointcutPublisher.versionStamp();
            assertTrue(v2 >= v1, "version should increase after revise");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void revise_triggersSubscriber() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "SubRevise.test", 1);

            final int[] count = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> count[0]++);

            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(1, System.nanoTime(), 0x39, 2, "SubRevise.test2");
            VmPointcutPublisher.revise(0, evt);

            assertEquals(1, count[0], "subscriber should be notified on revise");

            VmPointcutPublisher.unsubscribe(subId);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void revise_seq_preservedAcrossRevision() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x40, "SeqPreserve.test", 1);

            VmPointcutPublisher.PointcutEvent original = VmPointcutPublisher.peek(0);
            int originalSeq = original.seq;

            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(
                    originalSeq, System.nanoTime(), 0x41, 2, "SeqPreserve.test2");
            VmPointcutPublisher.revise(0, evt);

            VmPointcutPublisher.PointcutEvent after = VmPointcutPublisher.peek(0);
            assertEquals(originalSeq, after.seq, "seq should be preserved across revise");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void journalView_oldNanoAtIndex() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x48, "Journal.test", 1);

            VmPointcutPublisher.PointcutEvent original = VmPointcutPublisher.peek(0);
            long originalNano = original.nano;

            // Revise to trigger journal write
            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(
                    original.seq, System.nanoTime() + 1000, 0x49, 2, "Journal.test2");
            VmPointcutPublisher.revise(0, evt);

            long journaled = VmPointcutPublisher.journal().oldNanoAt(0);
            assertEquals(originalNano, journaled, "journal should store old nano before revise");
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