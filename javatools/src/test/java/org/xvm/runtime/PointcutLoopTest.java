package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: LOOP pointcut (0x70-0x7F) — control flow.
 * Kind = LOOP, before=true, after=false.
 */
public class PointcutLoopTest {

    @org.junit.jupiter.api.Test
    public void loop0x70_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x70), "LOOP(0x70) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void loop0x7F_beforeFlag() {
        assertTrue(VmPointcutDispatch.hasBeforeStatic(0x7F), "PAD(0x7F) should fire BEFORE");
    }

    @org.junit.jupiter.api.Test
    public void loopKindIsLoop() {
        for (int op = 0x70; op <= 0x7F; op++) {
            assertEquals(VmPointcutDispatch.Kind.LOOP, VmPointcutDispatch.kindOfStatic(op),
                "OP_0x" + Integer.toHexString(op) + " should be LOOP");
        }
    }

    @org.junit.jupiter.api.Test
    public void loopNoAfterFlag() {
        assertFalse(VmPointcutDispatch.hasAfterStatic(0x70), "LOOP(0x70) should NOT fire AFTER");
    }

    @org.junit.jupiter.api.Test
    public void publishLoop0x70_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x70, "org/example/MyClass.loop", 700);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x70, evts[0].opcode);
            // opcodeName() falls through to "OP_0x70" (no case for 0x70 in switch)
            assertEquals("org/example/MyClass.loop", evts[0].method);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishLoop0x7B_drainRoundTrip() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x7B, "org/example/MyClass.jmpFalse", 703);

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertEquals(0x7B, evts[0].opcode);
            assertEquals("JMP_FALSE", evts[0].opcodeName());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void publishAllLoopVariants_drainAllPresent() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            for (int op = 0x70; op <= 0x7F; op++) {
                VmPointcutPublisher.publish(op, "Loop.variant", op);
            }

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(16, evts.length);
            for (int i = 0; i < 16; i++) {
                assertEquals(0x70 + i, evts[i].opcode);
            }
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