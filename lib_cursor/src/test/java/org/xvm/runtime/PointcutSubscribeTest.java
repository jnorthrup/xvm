package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: subscribe / unsubscribe API and drain behaviour.
 */
public class PointcutSubscribeTest {

    @org.junit.jupiter.api.Test
    public void emptyDrain_returnsZero() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(0, evts.length);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void subscribe_receivesEventOnRevise() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            final int[] count = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> count[0]++);

            VmPointcutPublisher.publish(0x34, "Sub.test", 1);
            // drain shows publish worked
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);

            // subscriber only fires on revise, not on publish
            assertEquals(0, count[0], "subscriber should not fire on publish (only revise)");

            // Now revise — subscriber fires
            VmPointcutPublisher.PointcutEvent revised = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x35, 2, "Sub.test_revised");
            VmPointcutPublisher.revise(0, revised);
            assertEquals(1, count[0], "subscriber should fire on revise");

            VmPointcutPublisher.unsubscribe(subId);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void unsubscribe_stopsReviseEvents() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "Unsub.test", 1);
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);

            final int[] count = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> count[0]++);

            // First revise — fires
            VmPointcutPublisher.PointcutEvent rev1 = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x35, 2, "Unsub.test_rev1");
            VmPointcutPublisher.revise(0, rev1);
            assertEquals(1, count[0]);

            VmPointcutPublisher.unsubscribe(subId);

            // Second revise — should not fire
            VmPointcutPublisher.PointcutEvent rev2 = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x36, 3, "Unsub.test_rev2");
            VmPointcutPublisher.revise(0, rev2);
            assertEquals(1, count[0], "unsubscribed should not receive more revise events");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void multipleSubscribers_allReceiveRevise() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "Multi.test", 1);
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);

            final int[] a = {0};
            final int[] b = {0};
            int idA = VmPointcutPublisher.subscribe(evt -> a[0]++);
            int idB = VmPointcutPublisher.subscribe(evt -> b[0]++);

            // Revise — both subscribers fire
            VmPointcutPublisher.PointcutEvent rev = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x39, 2, "Multi.test_revised");
            VmPointcutPublisher.revise(0, rev);

            assertEquals(1, a[0], "subscriber A should receive revise");
            assertEquals(1, b[0], "subscriber B should receive revise");

            VmPointcutPublisher.unsubscribe(idA);

            // Second revise — only B receives
            VmPointcutPublisher.PointcutEvent rev2 = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x3A, 3, "Multi.test_revised2");
            VmPointcutPublisher.revise(0, rev2);

            assertEquals(1, a[0], "unsubscribed A should not receive second revise");
            assertEquals(2, b[0], "active B should receive second revise");

            VmPointcutPublisher.unsubscribe(idB);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void subscribe_returnsUniqueId() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            int id1 = VmPointcutPublisher.subscribe(evt -> {});
            int id2 = VmPointcutPublisher.subscribe(evt -> {});
            int id3 = VmPointcutPublisher.subscribe(evt -> {});

            assertTrue(id1 != id2, "subscribe ids should be unique");
            assertTrue(id2 != id3, "subscribe ids should be unique");

            VmPointcutPublisher.unsubscribe(id1);
            VmPointcutPublisher.unsubscribe(id2);
            VmPointcutPublisher.unsubscribe(id3);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void drainAfterSubscribe_sameEvents() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "DrainSub.test", 1);
            VmPointcutPublisher.publish(0x39, "DrainSub.test2", 2);

            final int[] subCount = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> subCount[0]++);

            // drain does not trigger subscribe callbacks (drain is observation, not notification)
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(2, evts.length);
            assertEquals(0, subCount[0], "drain should not trigger subscribers");

            VmPointcutPublisher.unsubscribe(subId);
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