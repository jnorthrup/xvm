package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: CONSTR pointcut (0x34-0x37).
 *
 * RED — verify dispatch table before/after flags, publish/drain round-trip.
 * GREEN — VmPointcutDispatch already has CONSTR (0x34-0x37) marked ALLOC, before=true.
 *
 * Wireproto: opcode byte = 0x34/0x35/0x36/0x37, kind = ALLOC.
 */
public class PointcutConstrTest {

    // ── dispatch table verification ─────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void constr0x34_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x34), "CONSTR_0(0x34) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void constr0x37_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x37), "CONSTR_T(0x37) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void constrKindIsAlloc() {
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x34));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x35));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x36));
        assertEquals(VmPointcutDispatch.Kind.ALLOC, VmPointcutDispatch.kindOfStatic(0x37));
    }

    // ── publish / drain round-trip ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void publishConstr0x34_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "org/example/MyClass.<init>", 100);

            VmPointcutPublisher.PointcutEvent[] events = drainAll();
            assertEquals(1, events.length);
            assertEquals(0x34, events[0].opcode);
            assertEquals("CONSTR_0", events[0].opcodeName());
            assertEquals("org/example/MyClass.<init>", events[0].method);
            assertEquals(100, events[0].addr);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishConstr0x37_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x37, "org/example/Foo.<init>", 200);

            VmPointcutPublisher.PointcutEvent[] events = drainAll();
            assertEquals(1, events.length);
            assertEquals(0x37, events[0].opcode);
            assertEquals("CONSTR_T", events[0].opcodeName());
            assertEquals("org/example/Foo.<init>", events[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishConstrAllVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "C0.<init>", 1);
            VmPointcutPublisher.publish(0x35, "C1.<init>", 2);
            VmPointcutPublisher.publish(0x36, "CN.<init>", 3);
            VmPointcutPublisher.publish(0x37, "CT.<init>", 4);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(4, evts.length);
            assertEquals(0x34, evts[0].opcode);
            assertEquals(0x35, evts[1].opcode);
            assertEquals(0x36, evts[2].opcode);
            assertEquals(0x37, evts[3].opcode);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private VmPointcutPublisher.PointcutEvent[] drainAll() {
        java.util.ArrayList<VmPointcutPublisher.PointcutEvent> list = new java.util.ArrayList<>();
        VmPointcutPublisher.drain(list::add);
        return list.toArray(new VmPointcutPublisher.PointcutEvent[0]);
    }
}