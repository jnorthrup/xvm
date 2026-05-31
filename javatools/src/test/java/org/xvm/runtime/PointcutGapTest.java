package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: GAP — unmapped opcodes return Kind.GAP and fire nothing.
 * Also tests out-of-range: < 0 and >= TABLE_SIZE.
 */
public class PointcutGapTest {

    @org.junit.jupiter.api.Test
    public void unmappedOpcode0x00_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(0x00));
    }

    @org.junit.jupiter.api.Test
    public void unmappedOpcode0x09_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(0x09));
    }

    @org.junit.jupiter.api.Test
    public void unmappedOpcode0x50_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(0x50));
    }

    @org.junit.jupiter.api.Test
    public void unmappedOpcode0x9F_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(0x9F));
    }

    @org.junit.jupiter.api.Test
    public void unmappedOpcode0xFF_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(0xFF));
    }

    @org.junit.jupiter.api.Test
    public void outOfRangeNegative_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(-1));
    }

    @org.junit.jupiter.api.Test
    public void outOfRange256_kindIsGap() {
        assertEquals(VmPointcutDispatch.Kind.GAP, VmPointcutDispatch.kindOfStatic(256));
    }

    @org.junit.jupiter.api.Test
    public void unmappedOpcode_noBefore() {
        assertFalse(VmPointcutDispatch.hasBeforeStatic(0x00));
        assertFalse(VmPointcutDispatch.hasBeforeStatic(0x50));
        assertFalse(VmPointcutDispatch.hasBeforeStatic(0xFF));
    }

    @org.junit.jupiter.api.Test
    public void unmappedOpcode_noAfter() {
        assertFalse(VmPointcutDispatch.hasAfterStatic(0x00));
        assertFalse(VmPointcutDispatch.hasAfterStatic(0x50));
        assertFalse(VmPointcutDispatch.hasAfterStatic(0xFF));
    }

    @org.junit.jupiter.api.Test
    public void publishUnmappedOpcode_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            // 0x50 is unmapped — still goes through publisher
            VmPointcutPublisher.publish(0x50, "Unmapped.method", 500);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x50, evts[0].opcode);
            assertEquals("OP_0x50", evts[0].opcodeName());
            assertEquals("Unmapped.method", evts[0].method);
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