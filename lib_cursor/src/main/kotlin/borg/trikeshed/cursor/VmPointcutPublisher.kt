package borg.trikeshed.cursor

import borg.trikeshed.lib.JournalSeries
import borg.trikeshed.lib.RingSeries
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.System.nanoTime

/**
 * CRUdux event publisher for VM pointcut journal.
 * RingSeries front-line (zero-copy, no GC) → JournalSeries for durable replay.
 *
 * C (Create)  — publish() every op readout from ServiceContext.dispatch
 * R (Read)     — ring.b(i) O(1) indexed read
 * U (Update)   — revise(i, e) in-place with old-value journaling
 * dux          — version=nanos, observable subscribers
 *
 * @see VmPointcutKind for available opcode tags
 */
@Suppress("NOTHING_TO_INLINE")
object VmPointcutPublisher {

    /** Front-line ring for firehose rate (>1K events/sec, no GC pressure) */
    val ring = RingSeries<PointcutEvent>(65536)

    /** Journal for durable replay / rollback */
    val journal = JournalSeries<PointcutEvent>()

    /** dux version stamp — increments on every revise() */
    @Volatile private var version: Long = 0L
    val versionStamp: Long get() = version

    /** Active — gate to avoid Allocation in hot path when disabled */
    @JvmField var active = false

    private val seq = AtomicInteger()

    /** Subscriber map */
    private val subs = ConcurrentHashMap<Int, (PointcutEvent) -> Unit>()

    /**
     * C — Create pointcut event at op readout.
     * Called from ServiceContext.dispatch on every op execution.
     */
    @JvmStatic
    fun publish(opcode: Int, method: String, addr: Int) {
        if (!active) return
        val evt = PointcutEvent(
            seq = seq.getAndIncrement(),
            nano = nanoTime(),
            opcode = VmPointcutKind.fromOpcode(opcode),
            addr = addr,
            method = method,
        )
        ring.add(evt)
    }

    /**
     * U — Revise an existing event at index (journals old value).
     * Used for back-fill corrections, annotation, re-timestamping.
     */
    fun revise(index: Int, evt: PointcutEvent) {
        val old = ring.b(index)
        journal.add(old) // journal the old value first
        ring.set(index, evt.copy(nano = nanoTime()))
        version = nanoTime()
        notify(evt)
    }

    /** R — Read event at index (O(1)). */
    @JvmStatic inline fun read(i: Int) = ring.b(i)

    /** Current ring size */
    @JvmStatic inline fun size(): Int = ring.a

    /**
     * dux subscribe — receive every revised event.
     * @return subscription id for unsubscribe()
     */
    @JvmStatic
    fun subscribe(fn: (PointcutEvent) -> Unit): Int {
        val id = seq.getAndIncrement()
        subs[id] = fn
        return id
    }

    /** Unsubscribe by id */
    @JvmStatic fun unsubscribe(id: Int) = subs.remove(id)

    /** Reset all state (for test isolation) */
    fun reset() {
        active = false
        version = 0L
    }

    private fun notify(evt: PointcutEvent) {
        subs.values.forEach { it(evt) }
    }

    /**
     * Pointcut event record — mirrors VmPointcutPublisher.PointcutEvent exactly.
     * All fields are immutable vals — JournalSeries-safe.
     */
    data class PointcutEvent(
        val seq: Int,
        val nano: Long,
        val opcode: VmPointcutKind,
        val addr: Int,
        val method: String,
    )

    /** Java-friendly static factory for PointcutEvent */
    @JvmStatic fun eventOf(s: Int, n: Long, o: VmPointcutKind, a: Int, m: String) =
        PointcutEvent(s, n, o, a, m)
}
