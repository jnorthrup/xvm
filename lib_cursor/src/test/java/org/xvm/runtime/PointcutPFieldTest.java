package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: P_GET / P_SET pointcut (0xA7 / 0xA8) — property get/set.
 *
 * Wireproto: opcode byte = 0xA7(P_GET), 0xA8(P_SET), kind = FIELD.
 */
public class PointcutPFieldTest {

    @org.junit.jupiter.api.Test
    public void pGet0xA7_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0xA7), "P_GET(0xA7) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void pSet0xA8_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0xA8), "P_SET(0xA8) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void pGetAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0xA7), "P_GET(0xA7) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void pSetAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0xA8), "P_SET(0xA8) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void kindIsField() {
        assertEquals(VmPointcutDispatch.Kind.FIELD, VmPointcutDispatch.kindOfStatic(0xA7));
        assertEquals(VmPointcutDispatch.Kind.FIELD, VmPointcutDispatch.kindOfStatic(0xA8));
    }

    @org.junit.jupiter.api.Test
    public void publishPGet0xA7_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA7, "org/example/MyClass.prop", 2000);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0xA7, evts[0].opcode);
            assertEquals("P_GET", evts[0].opcodeName());
            assertEquals("org/example/MyClass.prop", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishPSet0xA8_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA8, "org/example/MyClass.prop=", 2001);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0xA8, evts[0].opcode);
            assertEquals("P_SET", evts[0].opcodeName());
            assertEquals("org/example/MyClass.prop=", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishPGetPSet_bothPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA7, "FP.get", 1);
            VmPointcutPublisher.publish(0xA8, "FP.set", 2);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(2, evts.length);
            assertEquals(0xA7, evts[0].opcode);
            assertEquals(0xA8, evts[1].opcode);
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