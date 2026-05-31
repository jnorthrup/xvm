package borg.trikeshed.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.JournalSeries
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.Reducer
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * Cold WAL flow for typedef resolution events.
 * Stub — minimal API surface to unblock compilation.
 *
 * Real implementation: see TypedefResolutionSeries (pre-existing broken version).
 *
 * Pipeline:
 *   ChunkedMutableSeries (front-line)
 *     → JournalSeries (WAL rings, RING_SIZE entries each)
 *       → ReduxMutableSeries (cold cursor, journal replay)
 */
@Suppress("UNUSED", "UNCHECKED_CAST")
object TypedefResolutionSeries {

    const val RING_SIZE = 256
    const val RING_COUNT = 4

    // Named-tuple field indices
    const val FACTID = 0
    const val NANO = 1
    const val POOLID = 2
    const val SITEORD = 3
    const val CLSNAME_ID = 4
    const val FORMAT_ID = 5
    const val SUCCESS = 6
    const val IS_REVERTED = 7
    const val FIELD_COUNT = 8

    val FIELD_NAMES = listOf(
        "factId", "nano", "poolId", "siteOrd",
        "clsNameId", "formatId", "success", "isReverted"
    )

    // ── Fact factory ────────────────────────────────────────────────────────

    fun liveFact(
        factId: Long, nano: Long, poolId: Int, siteOrd: Int,
        clsName: String, format: String, success: Boolean
    ): Array<Any?> = arrayOf<Any?>(
        factId as Any?,
        nano as Any?,
        poolId as Any?,
        siteOrd as Any?,
        clsName as Any?,
        format as Any?,
        success as Any?,
        false as Any?
    )

    fun compensatingFact(
        factId: Long, nano: Long, poolId: Int, siteOrd: Int,
        clsName: String, format: String, originalSuccess: Boolean
    ): Array<Any?> = arrayOf<Any?>(
        factId as Any?,
        nano as Any?,
        poolId as Any?,
        siteOrd as Any?,
        clsName as Any?,
        format as Any?,
        originalSuccess as Any?,
        true as Any?
    )

    // ── Stub state ──────────────────────────────────────────────────────────

    private val nextFactId = java.util.concurrent.atomic.AtomicLong(0)
    private val factIndex = java.util.concurrent.ConcurrentHashMap<Long, Any>()
    private val frontLine = ChunkedMutableSeries<Any>(chunkSize = RING_SIZE)
    private val walRings = Array(RING_COUNT) { ChunkedMutableSeries<Any>(chunkSize = RING_SIZE) }
    private val walIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private object TypedefReducer : Reducer<Any, Map<String, Any>> {
        override val zero: Map<String, Any> = emptyMap()
        override fun combine(acc: Map<String, Any>, element: Any): Map<String, Any> = acc
    }

    val journal = ReduxMutableSeries<Any, Map<String, Any>>(
        eventJournal = frontLine,
        reducer = TypedefReducer,
        capture = Any()
    )

    // ── Accessors ──────────────────────────────────────────────────────────

    fun factById(factId: Long): Any? = factIndex[factId]

    private fun poolIdFromFact(e: Any?): Int {
        val arr = e as? Array<Any?> ?: return Int.MIN_VALUE
        return (arr[POOLID] as? Number)?.toInt() ?: Int.MIN_VALUE
    }

    private fun siteOrdFromFact(e: Any?): Int {
        val arr = e as? Array<Any?> ?: return Int.MIN_VALUE
        return (arr[SITEORD] as? Number)?.toInt() ?: Int.MIN_VALUE
    }

    private fun factIdFromFact(e: Any?): Long {
        val arr = e as? Array<Any?> ?: return Long.MIN_VALUE
        return (arr[FACTID] as? Number)?.toLong() ?: Long.MIN_VALUE
    }

    fun factsBySite(poolId: Int, siteOrd: Int): List<Any> {
        val result = mutableListOf<Any>()
        for (e in factIndex.values) {
            if (poolIdFromFact(e) == poolId && siteOrdFromFact(e) == siteOrd) result.add(e)
        }
        return result.sortedBy { factIdFromFact(it) }
    }

    fun factsByPool(poolId: Int): List<Any> {
        val result = mutableListOf<Any>()
        for (e in factIndex.values) {
            if (poolIdFromFact(e) == poolId) result.add(e)
        }
        return result.sortedBy { factIdFromFact(it) }
    }

    fun isReverted(fact: Any?): Boolean {
        val arr = fact as? Array<Any?> ?: return false
        return arr.get(IS_REVERTED) as? Boolean ?: false
    }

    fun delta(fact: Any?): Int = if (isReverted(fact)) -1 else +1

    // ── WAL ────────────────────────────────────────────────────────────────

    private fun flushWalRing() {
        val idx = walIndex.getAndIncrement() % RING_COUNT
        val ring = walRings[idx]
        val items = ring.toList()
        for (item in items) {
            journal.add(item)
        }
        ring.clear()
    }

    // ── Java API ───────────────────────────────────────────────────────────

    @JvmStatic
    fun record(poolId: Int, siteOrdinal: Int, className: String, formatName: String, success: Boolean): Long {
        val factId = nextFactId.getAndIncrement()
        val nano = System.nanoTime()
        val fact = liveFact(factId, nano, poolId, siteOrdinal, className, formatName, success)
        factIndex[factId] = fact
        frontLine.add(fact)
        val idx = walIndex.get() % RING_COUNT
        walRings[idx].add(fact)
        val ringSize = walRings[idx].a
        if (ringSize >= RING_SIZE) flushWalRing()
        return factId
    }

    @JvmStatic
    fun fact(factId: Long): Any? = factById(factId)

    @JvmStatic
    fun revert(factId: Long): Boolean {
        val f = factIndex.remove(factId) ?: return false
        val arr = f as? Array<Any?> ?: return false
        val nano = System.nanoTime()
        val poolId = (arr[POOLID] as? Number)?.toInt() ?: 0
        val siteOrd = (arr[SITEORD] as? Number)?.toInt() ?: 0
        val clsName = arr[CLSNAME_ID]?.toString() ?: "?"
        val fmt = arr[FORMAT_ID]?.toString() ?: "?"
        val origSucc = arr[SUCCESS] as? Boolean ?: false
        val comp = compensatingFact(factId, nano, poolId, siteOrd, clsName, fmt, origSucc)
        frontLine.add(comp)
        walRings[walIndex.get() % RING_COUNT].add(comp)
        return true
    }

    @JvmStatic
    fun revertSite(poolId: Int, siteOrd: Int): Int {
        val facts = factsBySite(poolId, siteOrd)
        for (f in facts) {
            factIndex.remove(factIdFromFact(f))
            val arr = f as? Array<Any?> ?: continue
            val nano = System.nanoTime()
            val clsName = arr[CLSNAME_ID]?.toString() ?: "?"
            val fmt = arr[FORMAT_ID]?.toString() ?: "?"
            val origSucc = arr[SUCCESS] as? Boolean ?: false
            val comp = compensatingFact(factIdFromFact(f), nano, poolId, siteOrd, clsName, fmt, origSucc)
            frontLine.add(comp)
            walRings[walIndex.get() % RING_COUNT].add(comp)
        }
        return facts.size
    }

    // toList: ChunkedMutableSeries implements Iterable
    @Suppress("UNCHECKED_CAST")
    private fun <T> ChunkedMutableSeries<T>.toList(): List<T> {
        val result = mutableListOf<T>()
        val iter = (this as Iterable<T>).iterator()
        while (iter.hasNext()) result.add(iter.next())
        return result
    }

    private operator fun <T> ChunkedMutableSeries<T>.get(i: Int): T = this.get(i)
}
