package org.xvm.runtime;

import borg.trikeshed.lib.EvictionListener;
import borg.trikeshed.lib.RingSeries;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Synapse journal for field pointcut events (P_GET, P_SET, L_GET, L_SET).
 *
 * Architecture:
 *   Producer: publish() → RingSeries(2048) [hot, zero-GC scratch buffer]
 *   Slab:     fire() or timeout → snapshot ring into immutable array
 *   Handoff:  slab → CowSeriesBody → subscriber notification
 *   Wireproto: slab → 24B records only when crossing process boundary
 *
 * Slab lifecycle:
 *   1. Producer writes into ring (O(1) add, zero allocation)
 *   2. When ring reaches 2048 (fire) OR timeout ticks with events:
 *      - drain ring into FieldSynapse[] snapshot (one arraycopy)
 *      - ring.clear() — producer continues on fresh ring
 *      - snapshot handed to subscriber as immutable slab
 *   3. Subscriber gets each slab for CRMS fold / cascade rollup
 *
 * Speculative underrun:
 *   If consumer is caught up and ring has N < 2048 events,
 *   timeout flush drains what's available — no starvation.
 *
 * Wireproto record (24 bytes, little-endian):
 *   offset  0: opcode       u8
 *   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
 *   offset  2: methodIdx    u16    — InternPool index
 *   offset  4: addr         i32
 *   offset  8: seq          i32
 *   offset 12: nano         i64
 *   offset 20: callsiteHash u16
 *   offset 22: templateIdx  u16
 */
public final class FieldSynapse {

    private static final int RING_CAP = 2048;
    public static final int RECORD_SIZE = 24;
    public static final int SLAB_SIZE = RING_CAP;

    // ── InternPool ────────────────────────────────────────────────────

    public static final class InternPool {
        private final String[] table = new String[65536];
        private final HashMap<String, Integer> index = new HashMap<>();
        private int next = 0;

        public synchronized int intern(String s) {
            return index.computeIfAbsent(s, k -> {
                int idx = next++;
                table[idx] = k;
                return idx;
            });
        }

        public String resolve(int idx) { return table[idx]; }
        public int size() { return next; }

        public byte[] toBytes() {
            int totalUtf8 = 0;
            for (int i = 0; i < next; i++) {
                totalUtf8 += table[i].getBytes(StandardCharsets.UTF_8).length;
            }
            ByteBuffer buf = ByteBuffer.allocate(2 + next * 4 + totalUtf8)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) next);
            for (int i = 0; i < next; i++) {
                byte[] utf8 = table[i].getBytes(StandardCharsets.UTF_8);
                buf.putShort((short) i);
                buf.putShort((short) utf8.length);
                buf.put(utf8);
            }
            byte[] result = new byte[buf.position()];
            buf.flip();
            buf.get(result);
            return result;
        }
    }

    public static final InternPool POOL = new InternPool();

    // ── Template strings ──────────────────────────────────────────────

    private static final int TPL_BEFORE_GET = POOL.intern("BEFORE %s.%s @ %d");
    private static final int TPL_AFTER_GET  = POOL.intern("AFTER  %s.%s @ %d →");
    private static final int TPL_BEFORE_SET = POOL.intern("BEFORE %s.%s @ %d =");
    private static final int TPL_AFTER_SET  = POOL.intern("AFTER  %s.%s @ %d ←");

    private static final int OP_L_GET = POOL.intern("L_GET");
    private static final int OP_L_SET = POOL.intern("L_SET");
    private static final int OP_P_GET = POOL.intern("P_GET");
    private static final int OP_P_SET = POOL.intern("P_SET");

    private static int opcodePoolIdx(int opcode) {
        return switch (opcode & 0xFF) {
            case 0xA5 -> OP_L_GET;
            case 0xA6 -> OP_L_SET;
            case 0xA7 -> OP_P_GET;
            case 0xA8 -> OP_P_SET;
            default -> POOL.intern("OP_0x" + Integer.toHexString(opcode & 0xFF));
        };
    }

    // ── Ring (producer scratch buffer) ────────────────────────────────

    private static final RingSeries<FieldSynapse> RING =
            new RingSeries<>(RING_CAP, (EvictionListener<FieldSynapse>) evt -> {});

    private static final AtomicInteger SEQ = new AtomicInteger();

    public static volatile boolean active = false;

    // ── Slab subscriber ───────────────────────────────────────────────

    /** Subscriber receives each slab on fire or timeout flush. */
    @FunctionalInterface
    public interface SlabSubscriber {
        void onSlab(FieldSynapse[] slab, int count, long epoch, long nanoStart, long nanoEnd);
    }

    private static volatile SlabSubscriber subscriber;
    private static long slabEpoch = 0;

    public static void setSubscriber(SlabSubscriber sub) { subscriber = sub; }

    // ── Record ────────────────────────────────────────────────────────

    public final byte phase;
    public final byte opcode;
    public final int  methodIdx;
    public final int  addr;
    public final int  seq;
    public final long nano;
    public final int  callsiteHash;
    public final int  templateIdx;

    private FieldSynapse(byte phase, byte opcode, int methodIdx, int addr,
                         int seq, long nano, int callsiteHash, int templateIdx) {
        this.phase = phase;
        this.opcode = opcode;
        this.methodIdx = methodIdx;
        this.addr = addr;
        this.seq = seq;
        this.nano = nano;
        this.callsiteHash = callsiteHash;
        this.templateIdx = templateIdx;
    }

    // ── Callsite hash ─────────────────────────────────────────────────

    public static int callsiteHash(int opcode, int methodIdx, int addr) {
        int h = 0x811c9dc5;
        h ^= opcode;          h *= 0x01000193;
        h ^= methodIdx;       h *= 0x01000193;
        h ^= (addr & 0xFF);   h *= 0x01000193;
        h ^= (addr >>> 8);    h *= 0x01000193;
        h ^= (addr >>> 16);   h *= 0x01000193;
        h ^= (addr >>> 24);   h *= 0x01000193;
        return h & 0xFFFF;
    }

    // ── Publish (hot path) ────────────────────────────────────────────

    /**
     * Publish a field pointcut event. Zero string allocation.
     * On slab fire (count == 2048): auto-flushes ring into subscriber.
     */
    public static void publishStatic(int opcode, String method, int addr, boolean isAfter) {
        if (!active) return;

        int methodIdx = POOL.intern(method);
        boolean isSet = (opcode & 0xFF) == 0xA6 || (opcode & 0xFF) == 0xA8;

        int tplIdx;
        if (!isAfter) {
            tplIdx = isSet ? TPL_BEFORE_SET : TPL_BEFORE_GET;
        } else {
            tplIdx = isSet ? TPL_AFTER_SET : TPL_AFTER_GET;
        }

        int csh = callsiteHash(opcode, methodIdx, addr);

        // record nano once for the event
        long nano = System.nanoTime();

        RING.add(new FieldSynapse(
                (byte) (isAfter ? 1 : 0),
                (byte) opcode,
                methodIdx,
                addr,
                SEQ.getAndIncrement(),
                nano,
                csh,
                tplIdx
        ));

        // SLAB FIRE: ring hit capacity → flush to subscriber
        if (RING.getA() == SLAB_SIZE) {
            flush("fire");
        }
    }

    // ── Slab flush ────────────────────────────────────────────────────

    /**
     * Flush current ring contents into an immutable slab.
     * Called on fire (capacity reached) or timeout (speculative underrun).
     *
     * One arraycopy. Ring clears. Subscriber gets immutable snapshot.
     */
    public static void flush(String reason) {
        int count = RING.getA();
        if (count == 0) return;

        // snapshot ring → immutable array
        FieldSynapse[] slab = new FieldSynapse[count];
        long nanoStart = Long.MAX_VALUE;
        long nanoEnd = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            FieldSynapse evt = RING.getB().invoke(i);
            slab[i] = evt;
            if (evt.nano < nanoStart) nanoStart = evt.nano;
            if (evt.nano > nanoEnd)   nanoEnd = evt.nano;
        }

        // clear ring — producer gets fresh scratch buffer
        RING.clear();

        // hand off to subscriber
        long epoch = slabEpoch++;
        SlabSubscriber sub = subscriber;
        if (sub != null) {
            sub.onSlab(slab, count, epoch, nanoStart, nanoEnd);
        }
    }

    /**
     * Timeout flush — drain what's available for speculative underrun.
     * Call from a timer tick. Only flushes if ring has events.
     */
    public static void timeoutFlush() {
        int count = RING.getA();
        if (count > 0 && count < SLAB_SIZE) {
            flush("timeout");
        }
    }

    // ── Query (for tests — prefer slabs for production) ───────────────

    public static int size() { return RING.getA(); }
    public static FieldSynapse peek(int i) { return RING.getB().invoke(i); }

    public static void drain(Consumer<FieldSynapse> consumer) {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            consumer.accept(RING.getB().invoke(i));
        }
    }

    // ── Lazy reification ──────────────────────────────────────────────

    public String reify() {
        String template = POOL.resolve(templateIdx);
        String opName = POOL.resolve(opcodePoolIdx(opcode));
        String methodName = POOL.resolve(methodIdx);
        return String.format(template, opName, methodName, addr);
    }

    public String opcodeName() { return POOL.resolve(opcodePoolIdx(opcode)); }
    public String methodName() { return POOL.resolve(methodIdx); }
    public String phaseLabel() { return phase == 0 ? "BEFORE" : "AFTER"; }
    public boolean isSet() { return (opcode & 0xFF) == 0xA6 || (opcode & 0xFF) == 0xA8; }

    // ── Wireproto ─────────────────────────────────────────────────────

    public static int wireprotoLength() { return size() * RECORD_SIZE; }

    public static void writeRecord(ByteBuffer target, int index) {
        FieldSynapse evt = RING.getB().invoke(index);
        target.put(evt.opcode);
        target.put(evt.phase);
        target.putShort((short) evt.methodIdx);
        target.putInt(evt.addr);
        target.putInt(evt.seq);
        target.putLong(evt.nano);
        target.putShort((short) evt.callsiteHash);
        target.putShort((short) evt.templateIdx);
    }

    public static ByteBuffer drainToWireproto() {
        int sz = size();
        ByteBuffer buf = ByteBuffer.allocate(sz * RECORD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sz; i++) {
            writeRecord(buf, i);
        }
        buf.flip();
        return buf;
    }

    public static FieldSynapse fromWireproto(ByteBuffer buf) {
        byte opcode = buf.get();
        byte phase = buf.get();
        int methodIdx = buf.getShort() & 0xFFFF;
        int addr = buf.getInt();
        int seq = buf.getInt();
        long nano = buf.getLong();
        int callsiteHash = buf.getShort() & 0xFFFF;
        int templateIdx = buf.getShort() & 0xFFFF;
        return new FieldSynapse(phase, opcode, methodIdx, addr, seq, nano, callsiteHash, templateIdx);
    }

    // ── Timer ─────────────────────────────────────────────────────────

    private static ScheduledExecutorService timer;
    private static ScheduledFuture<?> timerTask;

    /**
     * Start periodic timer tick for speculative underrun flush.
     * Daemon thread — won't prevent JVM exit.
     */
    public static void startTimer(long intervalMs) {
        stopTimer();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "field-synapse-timer");
            t.setDaemon(true);
            return t;
        });
        timerTask = timer.scheduleAtFixedRate(() -> {
            try {
                timeoutFlush();
            } catch (Exception e) {
                // swallow — timer must not kill the synapse
            }
        }, intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static void stopTimer() {
        if (timerTask != null) { timerTask.cancel(false); timerTask = null; }
        if (timer != null) { timer.shutdownNow(); timer = null; }
    }

    // ── Reset ─────────────────────────────────────────────────────────

    public static void reset() {
        stopTimer();
        active = false;
        RING.clear();
        SEQ.set(0);
        slabEpoch = 0;
        subscriber = null;
    }
}
