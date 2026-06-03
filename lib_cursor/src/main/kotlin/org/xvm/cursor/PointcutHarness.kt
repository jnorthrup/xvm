package org.xvm.cursor

import borg.trikeshed.lib.CollectorReducer
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.Series
import org.xvm.runtime.VmPointcutPublisher
import org.xvm.runtime.VmPointcutPublisher.PointcutEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Firehose harness: wraps VmPointcutPublisher with a ReduxMutableSeries journal
 * that captures every published event via drain() flush.
 *
 * Uses a flat JVM ArrayList backing to avoid the recursive-combine stack overflow
 * that TrikeShed's ChunkedMutableSeries/DequeSeries incur beyond ~65k depth.
 *
 * Contract: published == drained == decoded wire records, even past ring cap.
 *
 * Usage:
 *   val harness = PointcutHarness()
 *   VmPointcutPublisher.active = true
 *   // ... publish events ...
 *   VmPointcutPublisher.active = false
 *   harness.flush()          // drains all events into journal
 *   assertEquals(n, harness.capturedCount())
 */
class PointcutHarness {

    /** Flat JVM ArrayList wrapped as a MutableSeries — O(1) add, no recursion. */
    private val list = ArrayList<PointcutEvent>(1 shl 17)

    private val journal: MutableSeries<PointcutEvent> = object : MutableSeries<PointcutEvent> {
        override val a: Int get() = list.size
        override val b: (Int) -> PointcutEvent = { i -> list[i] }
        override fun add(item: PointcutEvent) { list.add(item) }
        override fun add(index: Int, item: PointcutEvent) { list.add(index, item) }
        override fun set(index: Int, item: PointcutEvent) { list[index] = item }
        override fun removeAt(index: Int): PointcutEvent = list.removeAt(index)
        override fun remove(item: PointcutEvent): Boolean = list.remove(item)
        override fun clear() { list.clear() }
        override fun plus(item: PointcutEvent): MutableSeries<PointcutEvent> = apply { add(item) }
        override fun minus(item: PointcutEvent): MutableSeries<PointcutEvent> = apply { remove(item) }
        override fun plusAssign(item: PointcutEvent) { add(item) }
        override fun minusAssign(item: PointcutEvent) { remove(item) }
    }

    private val reducer = CollectorReducer<PointcutEvent>()
    private val capture: PointcutEvent = PointcutEvent(0, 0L, 0, 0, "__capture__")

    val series: ReduxMutableSeries<PointcutEvent, Series<PointcutEvent>> =
        ReduxMutableSeries(journal, reducer, reducer.zero, capture)

    // subscribe/unsubscribe stubs — retained for test API compatibility
    // The harness uses drain-based capture, not subscription, so subscribers
    // don't fire on publish (consistent with VmPointcutPublisher semantics).
    private var subId: Int = -1

    fun subscribe(): Int {
        // no-op: harness uses drain, not subscribe
        subId = 0
        return subId
    }

    fun unsubscribe() {
        subId = -1
    }

    /**
     * Drain all published events from VmPointcutPublisher into the journal.
     * Call after publishing to capture events.
     */
    fun flush() {
        VmPointcutPublisher.drain { evt -> series.dispatch(evt) }
    }

    fun capturedCount(): Int = journal.a

    fun eventAt(index: Int): PointcutEvent = journal.b(index)

    fun reify(): Series<PointcutEvent> = series.reify()

    fun drainToWireproto(): ByteBuffer {
        val sz = capturedCount()
        val buf = ByteBuffer.allocate(sz * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sz) {
            val evt = eventAt(i)
            buf.put(evt.opcode.toByte())
            buf.put(0)
            buf.putShort(evt.methodIdx.toShort())
            buf.putInt(evt.addr)
            buf.putInt(evt.seq)
            buf.putLong(evt.nano)
        }
        buf.flip()
        return buf
    }

    fun fromWireproto(buf: ByteBuffer): PointcutEvent {
        val opcode = buf.get().toInt() and 0xFF
        buf.get()
        val methodIdx = buf.getShort().toInt() and 0xFFFF
        val addr = buf.getInt()
        val seq = buf.getInt()
        val nano = buf.getLong()
        return PointcutEvent(seq, nano, opcode, addr, methodIdx)
    }

    companion object {
        const val RECORD_SIZE: Int = VmPointcutPublisher.RECORD_SIZE
    }
}
