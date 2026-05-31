package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: L_GET / L_SET pointcut (0xA5 / 0xA6) — long-get / long-set field access.
 *
 * Wireproto: opcode byte = 0xA5(L_GET), 0xA6(L_SET), kind = FIELD.
 */
public class PointcutLFieldTest {

    @org.junit.jupiter.api.Test
    public void lGet0xA5_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0xA5), "L_GET(0xA5) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void lSet0xA6_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0xA6), "L_SET(0xA6) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void lGetAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0xA5), "L_GET(0xA5) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void lSetAfterFlag() {
        assertTrue(VmPointcutDispatch.hasAfterStatic(0xA6), "L_SET(0xA6) should fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void kindIsField() {
        assertEquals(VmPointcutDispatch.Kind.FIELD, VmPointcutDispatch.kindOfStatic(0xA5));
        assertEquals(VmPointcutDispatch.Kind.FIELD, VmPointcutDispatch.kindOfStatic(0xA6));
    }

    @org.junit.jupiter.api.Test
    public void publishLGet0xA5_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA5, "org/example/MyClass.field", 1000);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0xA5, evts[0].opcode);
            assertEquals("L_GET", evts[0].opcodeName());
            assertEquals("org/example/MyClass.field", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishLSet0xA6_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA6, "org/example/MyClass.field=", 1001);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0xA6, evts[0].opcode);
            assertEquals("L_SET", evts[0].opcodeName());
            assertEquals("org/example/MyClass.field=", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishLGetLSet_bothPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0xA5, "FG.get", 1);
            VmPointcutPublisher.publish(0xA6, "FG.set", 2);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(2, evts.length);
            assertEquals(0xA5, evts[0].opcode);
            assertEquals(0xA6, evts[1].opcode);
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