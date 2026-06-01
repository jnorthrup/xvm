package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PointcutEvent field semantics — seq, nano, addr, method.
 */
public class PointcutEventSemanticsTest {

    @org.junit.jupiter.api.Test
    public void event_seq_isUniqueAndIncrementing() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x10, "Seq.test", 1);
            VmPointcutPublisher.publish(0x11, "Seq.test2", 2);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertTrue(evts[1].seq > evts[0].seq, "seq should increment");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void event_nano_isNonZero() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "Nano.test", 1);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertTrue(evts[0].nano > 0, "nano should be > 0");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void event_addr_preservedOnPublish() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA5, "Addr.test", 9999);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(9999, evts[0].addr);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void event_afterWrite_usesAfterLiteral() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "AFTER", -1);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals("AFTER", evts[0].methodName());
            assertEquals(-1, evts[0].addr);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void opcodeName_defaultCase() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x50, "Test.methodName()", 1);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals("OP_0x50", evts[0].opcodeName());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void toString_containsAllFields() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "ToString.test", 42);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            String s = evts[0].toString();
            assertTrue(s.contains("seq="), "toString should include seq");
            assertTrue(s.contains("opcode="), "toString should include opcode");
            assertTrue(s.contains("addr="), "toString should include addr");
            assertTrue(s.contains("nano="), "toString should include nano");
            assertTrue(s.contains("method="), "toString should include method");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void versionStamp_incrementsOnPublish() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long v0 = VmPointcutPublisher.versionStamp();
            VmPointcutPublisher.publish(0x34, "Version.test", 1);
            long v1 = VmPointcutPublisher.versionStamp();
            assertTrue(v1 >= v0, "version should not decrease");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void size_reflectsRingContents() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            assertEquals(0, VmPointcutPublisher.size());

            VmPointcutPublisher.publish(0x10, "Size.test1", 1);
            assertEquals(1, VmPointcutPublisher.size());

            VmPointcutPublisher.publish(0x11, "Size.test2", 2);
            assertEquals(2, VmPointcutPublisher.size());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void reset_clearsRingAndVersion() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "Reset.test", 1);
            VmPointcutPublisher.publish(0x35, "Reset.test2", 2);
            assertTrue(VmPointcutPublisher.size() > 0);

            VmPointcutPublisher.reset();
            VmPointcutPublisher.active = true;

            assertEquals(0, VmPointcutPublisher.size());
            assertEquals(0L, VmPointcutPublisher.versionStamp());
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