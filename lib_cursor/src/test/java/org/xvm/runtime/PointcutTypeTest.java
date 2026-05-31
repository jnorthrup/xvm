package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: MOV_TYPE (0x65) and CAST (0x66) — type operations.
 * Kind = TYPE, before+after both true.
 */
public class PointcutTypeTest {

    @org.junit.jupiter.api.Test
    public void movType0x65_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x65), "MOV_TYPE(0x65) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void movType0x65_afterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x65), "MOV_TYPE(0x65) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void movTypeKindIsType() {
        assertEquals(VmPointcutDispatch.Kind.TYPE, VmPointcutDispatch.kindOfStatic(0x65));
    }

    @org.junit.jupiter.api.Test
    public void cast0x66_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x66), "CAST(0x66) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void cast0x66_afterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0x66), "CAST(0x66) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void castKindIsType() {
        assertEquals(VmPointcutDispatch.Kind.TYPE, VmPointcutDispatch.kindOfStatic(0x66));
    }

    @org.junit.jupiter.api.Test
    public void publishMovType_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x65, "org/example/MyClass.<type>", 650);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x65, evts[0].opcode);
            assertEquals("MOV_TYPE", evts[0].opcodeName());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishCast_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x66, "org/example/MyClass.<cast>", 660);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x66, evts[0].opcode);
            assertEquals("CAST", evts[0].opcodeName());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishMovTypeAndCast_bothPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x65, "MT.mov", 1);
            VmPointcutPublisher.publish(0x66, "CAST.cast", 2);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(2, evts.length);
            assertEquals(0x65, evts[0].opcode);
            assertEquals(0x66, evts[1].opcode);
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