package org.xvm.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.Reducer
import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.view

/**
 * Cold event log for typedef resolution events.
 *
 * frontLine is the event journal.
 * WAL rings are transient buffers only.
 * ReduxMutableSeries is exposed as a cold wrapper over the same event log,
 * but capture APIs below dump raw events rather than reducer-derived state.
 */
data class TypedefFact(
    val factId: Long,
    val nano: Long,
    val poolId: Int,
    val siteOrd: Int,
    val clsName: String,
    val format: String,
    val success: Boolean,
    val isReverted: Boolean = false
)

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

    // ── State ──────────────────────────────────────────────────────────

    private val nextFactId = java.util.concurrent.atomic.AtomicLong(0)
    private val factIndex = java.util.concurrent.ConcurrentHashMap<Long, TypedefFact>()
    private var frontLine = ChunkedMutableSeries<TypedefFact>(chunkSize = RING_SIZE)
    private var walRings = Array(RING_COUNT) { ChunkedMutableSeries<TypedefFact>(chunkSize = RING_SIZE) }
    private val walIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private object TypedefReducer : Reducer<TypedefFact, Map<Long, TypedefFact>> {
        override val zero: Map<Long, TypedefFact> = emptyMap()
        override fun combine(acc: Map<Long, TypedefFact>, element: TypedefFact): Map<Long, TypedefFact> {
            val mut = acc.toMutableMap()
            if (element.isReverted) {
                mut.remove(element.factId)
            } else {
                mut[element.factId] = element
            }
            return mut
        }
    }

    private fun newJournal() = ReduxMutableSeries<TypedefFact, Map<Long, TypedefFact>>(
        eventJournal = frontLine,
        reducer = TypedefReducer,
        capture = TypedefFact(-1L, 0L, 0, 0, "", "", false, false)
    )

    @get:JvmName("journal")
    var journal = newJournal()
        private set

    // ── Accessors ──────────────────────────────────────────────────────────

    fun factsBySite(poolId: Int, siteOrd: Int): List<TypedefFact> {
        val result = mutableListOf<TypedefFact>()
        for (e in factIndex.values) {
            if (e.poolId == poolId && e.siteOrd == siteOrd) result.add(e)
        }
        return result.sortedBy { it.factId }
    }

    fun factsByPool(poolId: Int): List<TypedefFact> {
        val result = mutableListOf<TypedefFact>()
        for (e in factIndex.values) {
            if (e.poolId == poolId) result.add(e)
        }
        return result.sortedBy { it.factId }
    }

    // ── WAL ────────────────────────────────────────────────────────────────

    private fun flushWalRing() {
        val idx = walIndex.getAndIncrement() % RING_COUNT
        val ring = walRings[idx]
        ring.clear()
    }

    // ── Java API ───────────────────────────────────────────────────────────

    @JvmStatic
    fun record(poolId: Int, siteOrdinal: Int, className: String, formatName: String, success: Boolean): Long {
        val factId = nextFactId.getAndIncrement()
        val nano = System.nanoTime()
        val fact = TypedefFact(factId, nano, poolId, siteOrdinal, className, formatName, success)
        factIndex[factId] = fact
        frontLine.add(fact)
        val idx = walIndex.get() % RING_COUNT
        walRings[idx].add(fact)
        val ringSize = walRings[idx].a
        if (ringSize >= RING_SIZE) flushWalRing()
        return factId
    }

    @JvmStatic
    fun fact(factId: Long): Any? = factIndex[factId]

    @JvmStatic
    fun revert(factId: Long): Boolean {
        val f = factIndex.remove(factId) ?: return false
        val comp = f.copy(nano = System.nanoTime(), isReverted = true)
        frontLine.add(comp)
        walRings[walIndex.get() % RING_COUNT].add(comp)
        return true
    }

    @JvmStatic
    fun revertSite(poolId: Int, siteOrd: Int): Int {
        val facts = factsBySite(poolId, siteOrd)
        for (f in facts) {
            factIndex.remove(f.factId)
            val comp = f.copy(nano = System.nanoTime(), isReverted = true)
            frontLine.add(comp)
            walRings[walIndex.get() % RING_COUNT].add(comp)
        }
        return facts.size
    }

    @JvmStatic
    fun revertPool(poolId: Int): Int {
        val facts = factsByPool(poolId)
        for (f in facts) {
            factIndex.remove(f.factId)
            val comp = f.copy(nano = System.nanoTime(), isReverted = true)
            frontLine.add(comp)
            walRings[walIndex.get() % RING_COUNT].add(comp)
        }
        return facts.size
    }

    @JvmStatic
    fun drain(): Int {
        var count = 0
        for (i in 0 until RING_COUNT) {
            val ring = walRings[i]
            val ringSize = ring.a
            if (ringSize > 0) {
                count += ringSize
                ring.clear()
            }
        }
        return count
    }

    @JvmStatic
    fun size(): Int {
        drain()
        return frontLine.a
    }

    @JvmStatic
    fun reset() {
        nextFactId.set(0)
        factIndex.clear()
        frontLine = ChunkedMutableSeries(chunkSize = RING_SIZE)
        walRings = Array(RING_COUNT) { ChunkedMutableSeries(chunkSize = RING_SIZE) }
        walIndex.set(0)
        journal = newJournal()
    }

    @JvmStatic
    fun metaSeries(): Any {
        return journal
    }

    @JvmStatic
    fun snapshotEvents(): List<TypedefFact> {
        drain()
        return frontLine.view
            .map { it.copy() }
    }

    @JvmStatic
    fun snapshotFacts(): List<TypedefFact> = snapshotEvents()

    @JvmStatic
    fun toRowVec(): String {
        val events = snapshotEvents()
        val keys = events.map { it.factId }.joinToString(",")
        val cells = events.joinToString(";") {
            "${it.poolId},${it.siteOrd},${it.clsName},${it.format},${it.success},${it.nano}"
        }
        return "$keys|$cells"
    }
}
