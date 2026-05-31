package org.xvm.runtime;

import borg.trikeshed.lib.EvictionListener;
import borg.trikeshed.lib.RingSeries;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CRUdux event publisher for VM pointcut journal.
 * Backed by borg.trikeshed.lib.RingSeries (TrikeShed).
 *
 * ring[] is a RingSeries (power-of-2 capacity, functional index access).
 * JOURNAL[] stores old nano timestamps for rollback.
 *
 * C (Create)  — publish() every op readout from ServiceContext.doOneOp()
 * R (Read)    — drain(consumer) / peek(i) for observation layer
 * U (Update)  — revise(i, e) in-place with old-value journaling
 * dux         — version=nanos, observable subscribers
 *
 * @see VmPointcutKind for available opcode tags (lib_cursor side)
 */
public final class VmPointcutPublisher {

    static {
        // Wire VmPointcutPublisher + FieldSynapse into ServiceContext
        ServiceContext.pointcut = new ServiceContext.PointcutHook() {
            @Override public boolean active() { return VmPointcutPublisher.active; }
            @Override public void publish(int opcode, String method, int addr) { VmPointcutPublisher.publish(opcode, method, addr); }
            @Override public void fieldPublish(int opcode, String method, int addr, boolean after) { FieldSynapse.publishStatic(opcode, method, addr, after); }
        };
    }

    /** Ring capacity — power of 2 for cheap masking */
    private static final int CAP = 65536;

    /** No-op eviction listener — ring discards oldest on overflow */
    private static final EvictionListener<PointcutEvent> NO_OP = evt -> {};

    /** TrikeShed RingSeries: add() for C, getB().invoke(i) for R, getA() for size */
    private static final RingSeries<PointcutEvent> RING =
            new RingSeries<>(CAP, NO_OP);

    /** Journal for rollback — key=slot, value=old nano */
    private static final long[] JOURNAL = new long[CAP];

    /** dux version stamp — increments on every revise() */
    private static volatile long version = 0L;
    public static long versionStamp() { return version; }

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Active — gate to avoid allocation in hot path when disabled */
    public static volatile boolean active = false;

    /** Total publish() invocations (ungated — counts even when inactive) */
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_INVOKED = new java.util.concurrent.atomic.AtomicLong();
    public static long totalInvoked() { return TOTAL_INVOKED.get(); }

    private static final ConcurrentHashMap<Integer, Consumer<PointcutEvent>> SUBS =
            new ConcurrentHashMap<>();

    /**
     * C — Create pointcut event at op readout.
     * Called from ServiceContext.dispatch on every op execution.
     *
     * @param opcode  raw opcode integer from Op.getOpCode()
     * @param method  fully-qualified method name
     * @param addr    current instruction pointer (PC)
     */
    public static void publish(int opcode, String method, int addr) {
        TOTAL_INVOKED.incrementAndGet();
        if (!active) return;
        RING.add(new PointcutEvent(
                SEQ.getAndIncrement(),
                System.nanoTime(),
                opcode,
                addr,
                method
        ));
    }

    /**
     * Events currently in ring — delegates to RingSeries.getA().
     */
    public static int size() {
        return RING.getA();
    }

    /**
     * R — Peek event at logical index i (0 = oldest).
     * Fires eviction listener if ring overflowed during iteration.
     */
    public static PointcutEvent peek(int i) {
        return RING.getB().invoke(i);
    }

    /**
     * U — Revise event at logical index i (journals old nano timestamp).
     */
    public static void revise(int index, PointcutEvent evt) {
        JOURNAL[index] = RING.getB().invoke(index).nano; // journal old nano for rollback
        RING.set(index, new PointcutEvent(evt.seq, System.nanoTime(), evt.opcode, evt.addr, evt.method));
        version = System.nanoTime();
        notify(evt);
    }

    /**
     * Drain all current events to the consumer (0 = oldest).
     * Useful for observation layer: drainToArray(consumer) or dump.
     */
    public static void drain(Consumer<PointcutEvent> consumer) {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            consumer.accept(RING.getB().invoke(i));
        }
    }

    /**
     * Drain all current events into a sink.
     * Decoupled from TypedefCascadeTable — accepts a raw (opcode,method,addr) consumer.
     *
     * @param foldSink consumer that receives (opcode, method, addr) for each event
     */
    public static void drainOpcodes(java.util.function.IntConsumer opcodeSink) {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            PointcutEvent evt = RING.getB().invoke(i);
            opcodeSink.accept(evt.opcode);
        }
    }

    /**
     * Drain all current events into a tri-consumer (opcode, method, addr).
     */
    public static void drainAll(java.util.function.ObjIntConsumer<String> sink) {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            PointcutEvent evt = RING.getB().invoke(i);
            sink.accept(evt.method, evt.opcode);
        }
    }

    /**
     * Ring accessor for observation layer.
     */
    public static RingView ring() { return new RingView(); }

    public static final class RingView {
        public int head() { return 0; } // RingSeries has no exposed write head
        public int cap()  { return CAP; }
        public int size() { return RING.getA(); }
    }

    /** Journal accessor */
    public static JournalView journal() { return new JournalView(); }

    public static final class JournalView {
        public long oldNanoAt(int index) { return JOURNAL[index]; }
    }

    /**
     * dux subscribe — receive every revised event.
     * @return subscription id for unsubscribe()
     */
    public static int subscribe(Consumer<PointcutEvent> fn) {
        int id = SEQ.getAndIncrement();
        SUBS.put(id, fn);
        return id;
    }

    /** Unsubscribe by id */
    public static void unsubscribe(int id) { SUBS.remove(id); }

    /** Reset ring state (for test isolation) */
    public static void reset() {
        active = false;
        version = 0L;
        TOTAL_INVOKED.set(0);
        RING.clear();
        for (int i = 0; i < CAP; i++) {
            JOURNAL[i] = 0L;
        }
    }

    private static void notify(PointcutEvent evt) {
        SUBS.values().forEach(fn -> fn.accept(evt));
    }

    /**
     * Pointcut event record.
     * All fields final — immutable, copy-on-write safe.
     */
    public static final class PointcutEvent {
        public final int seq;
        public final long nano;
        public final int opcode;
        public final int addr;
        public final String method;

        public PointcutEvent(int seq, long nano, int opcode, int addr, String method) {
            this.seq = seq;
            this.nano = nano;
            this.opcode = opcode;
            this.addr = addr;
            this.method = method;
        }

        /** Human-readable opcode name */
        public String opcodeName() {
            switch (opcode) {
                case 0x10: return "CALL_00"; case 0x11: return "CALL_01"; case 0x12: return "CALL_0N"; case 0x13: return "CALL_0T";
                case 0x14: return "CALL_10"; case 0x15: return "CALL_11"; case 0x16: return "CALL_1N"; case 0x17: return "CALL_1T";
                case 0x18: return "CALL_N0"; case 0x19: return "CALL_N1"; case 0x1A: return "CALL_NN"; case 0x1B: return "CALL_NT";
                case 0x1C: return "CALL_T0"; case 0x1D: return "CALL_T1"; case 0x1E: return "CALL_TN"; case 0x1F: return "CALL_TT";
                case 0x20: return "NVOK_00"; case 0x21: return "NVOK_01"; case 0x22: return "NVOK_0N"; case 0x23: return "NVOK_0T";
                case 0x24: return "NVOK_10"; case 0x25: return "NVOK_11"; case 0x26: return "NVOK_1N"; case 0x27: return "NVOK_1T";
                case 0x28: return "NVOK_N0"; case 0x29: return "NVOK_N1"; case 0x2A: return "NVOK_NN"; case 0x2B: return "NVOK_NT";
                case 0x2C: return "NVOK_T0"; case 0x2D: return "NVOK_T1"; case 0x2E: return "NVOK_TN"; case 0x2F: return "NVOK_TT";
                case 0x33: return "SYN_INIT";
                case 0x34: return "CONSTR_0"; case 0x35: return "CONSTR_1"; case 0x36: return "CONSTR_N"; case 0x37: return "CONSTR_T";
                case 0x38: return "NEW_0";   case 0x39: return "NEW_1";   case 0x3A: return "NEW_N";   case 0x3B: return "NEW_T";
                case 0x40: return "NEWC_0";  case 0x41: return "NEWC_1";  case 0x42: return "NEWC_N";  case 0x43: return "NEWC_T";
                case 0x48: return "NEWV_0";  case 0x49: return "NEWV_1";  case 0x4A: return "NEWV_N";  case 0x4B: return "NEWV_T";
                case 0x4C: return "RETURN_0"; case 0x4D: return "RETURN_1"; case 0x4E: return "RETURN_N"; case 0x4F: return "RETURN_T";
                case 0x65: return "MOV_TYPE"; case 0x66: return "CAST";
                case 0x77: return "LOOP";     case 0x78: return "LOOP_END";
                case 0x79: return "JMP";      case 0x7A: return "JMP_TRUE";  case 0x7B: return "JMP_FALSE";
                case 0x90: return "ASSERT";   case 0x91: return "ASSERT_M";  case 0x92: return "ASSERT_V";
                case 0xA5: return "L_GET";   case 0xA6: return "L_SET";
                case 0xA7: return "P_GET";   case 0xA8: return "P_SET";
                default:   return "OP_0x" + Integer.toHexString(opcode);
            }
        }

        @Override public String toString() {
            return "PointcutEvent{seq=" + seq + ", opcode=" + opcodeName() +
                    "(0x" + Integer.toHexString(opcode) + "), addr=" + addr +
                    ", method=" + method + '}';
        }
    }
}
