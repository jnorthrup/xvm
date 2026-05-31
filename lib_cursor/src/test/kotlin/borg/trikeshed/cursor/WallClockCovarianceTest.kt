package borg.trikeshed.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.JournalSeries
import borg.trikeshed.lib.MergeMutableSeries
import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.α
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList
import org.junit.jupiter.api.*
import java.lang.System.nanoTime
import kotlin.math.sqrt

/**
 * Wall-clock covariance / Pearson-correlation tests for all MutableSeries types.
 *
 * Measures the temporal correlation between event sequence position (i) and wall-clock
 * timestamp (t) for each operation. In a perfectly linear time series, r ≈ 1.0.
 * Any GC pause, JIT compilation spike, or thread contention will spike covariance
 * and depress the correlation coefficient.
 *
 * This is NOT a performance benchmark — it is a _stability_ probe. High correlation
 * means predictable, uniform latency. Low correlation (r < 0.9) flags jitter.
 *
 * Run with:
 *   ./gradlew :lib_cursor:test -Pjmh=false
 */
@DisplayName("Wall Clock Covariance — MutableSeries stability probe")
class WallClockCovarianceTest {

    // ── Pointcut event wire format ─────────────────────────────────────────────

    data class PointcutEvent(
        val seq: Int,
        val nano: Long,
        val opcode: String,
        val addr: Long,
        val cls: String,
    ) {
        companion object {
            private val opcodes = listOf(
                "LOAD", "STORE", "BRANCH", "CALL", "RETURN", "ADD", "SUB", "MULT",
                "DIV", "AND", "OR", "XOR", "SHIFT_L", "SHIFT_R", "CBRANCH", "LABEL"
            )
            fun random(seq: Int): PointcutEvent = PointcutEvent(
                seq = seq,
                nano = nanoTime(),
                opcode = opcodes[seq % opcodes.size],
                addr = (Math.random() * 0x10000).toLong(),
                cls = "Lorg/xvm/runtime/Frame;"
            )
        }
    }

    // ── Pearson R: two-pass mean-centered to avoid catastrophic cancellation ──

    fun pearsonR(events: Series<PointcutEvent>): Double {
        if (events.size < 3) return Double.NaN
        val n = events.size.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        for (e in events.toList()) {
            sumX += e.seq.toDouble()
            sumY += e.nano.toDouble()
        }
        val meanX = sumX / n
        val meanY = sumY / n
        var num = 0.0
        var denX2 = 0.0
        var denY2 = 0.0
        for (e in events.toList()) {
            val dx = e.seq.toDouble() - meanX
            val dy = e.nano.toDouble() - meanY
            num += dx * dy
            denX2 += dx * dx
            denY2 += dy * dy
        }
        val den = sqrt(denX2 * denY2)
        return if (den == 0.0) Double.NaN else num / den
    }

    fun interArrivalStdev(events: Series<PointcutEvent>): Double {
        if (events.size < 2) return Double.NaN
        val deltas = events.toList().zipWithNext().map { (a, b) -> (b.nano - a.nano).toDouble() }
        val mean = deltas.fold(0.0) { acc, v -> acc + v } / deltas.size
        val variance = deltas.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / (deltas.size - 1)
        return sqrt(variance)
    }

    fun latencyStats(events: Series<PointcutEvent>): LatencyStats {
        if (events.size < 2) return LatencyStats(Double.NaN, Double.NaN, Double.NaN)
        val deltas = events.toList().zipWithNext().map { (a, b) -> b.nano - a.nano }
        val vals = deltas.toList()
        return LatencyStats(
            vals.minOrNull()!!.toDouble(),
            vals.maxOrNull()!!.toDouble(),
            vals.sumOf { it.toDouble() } / vals.size,
        )
    }

    data class LatencyStats(val minNs: Double, val maxNs: Double, val avgNs: Double) {
        override fun toString() = "min=%.1fns max=%.1fns avg=%.1fns".format(minNs, maxNs, avgNs)
    }

    // ── RingSeries ────────────────────────────────────────────────────────────

    @Test
    fun `RingSeries wall clock covariance`() {
        val ring = RingSeries<PointcutEvent>(65536)
        // Warmup: prime JIT + fill buffers
        repeat(10_000) { ring.add(PointcutEvent.random(it)) }
        ring.clear()

        val captured = mutableListOf<PointcutEvent>()
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())
        }

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        val lat = latencyStats(captured.toSeries())
        println("RingSeries: r=$r jitter=${"%.1f".format(jitter)}ns $lat")

        Assertions.assertTrue(r >= 0.99, "RingSeries wall clock correlation r=$r < 0.99")
        Assertions.assertTrue(jitter < 500_000.0, "RingSeries inter-arrival stdev ${"%.1f".format(jitter)}ns > 500µs")
    }

    @Test
    fun `RingSeries eviction covariance`() {
        var evictedCount = 0
        val ring = RingSeries<PointcutEvent>(256) { evictedCount++ }
        repeat(10_000) { ring.add(PointcutEvent.random(it)) }
        ring.clear()
        evictedCount = 0

        val captured = mutableListOf<PointcutEvent>()
        repeat(100_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())
        }

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        println("RingSeries eviction: r=$r jitter=${"%.1f".format(jitter)}ns evicted=$evictedCount")

        Assertions.assertTrue(r >= 0.98, "RingSeries eviction r=$r < 0.98")
        Assertions.assertTrue(evictedCount > 0, "Eviction callback should have fired")
    }

    // ── MergeMutableSeries ───────────────────────────────────────────────────

    @Test
    fun `MergeMutableSeries wall clock covariance below threshold`() {
        val m = MergeMutableSeries<PointcutEvent>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val captured = mutableListOf<PointcutEvent>()

        // 5k ops with threshold=64: pending COW grows as O(n) but no compaction yet
        repeat(5_000) { i ->
            captured.add(PointcutEvent.random(i))
            m.add(captured.last())
        }

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        println("MergeMutableSeries below-threshold: r=$r jitter=${"%.1f".format(jitter)}ns")

        // pending is RecursiveMutableSeries (COW) — O(n) cost grows with n even below threshold
        Assertions.assertTrue(r >= 0.95, "MergeMutableSeries below-threshold r=$r < 0.95")
        Assertions.assertTrue(jitter < 2_000_000.0, "MergeMutableSeries below-threshold jitter ${"%.1f".format(jitter)}ns > 2ms")
    }

    @Test
    fun `MergeMutableSeries wall clock covariance through compaction`() {
        val m = MergeMutableSeries<PointcutEvent>(mergeThreshold = 1024) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(10_000) { m.add(PointcutEvent.random(it)) }
        m.flush()

        val captured = mutableListOf<PointcutEvent>()
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            m.add(captured.last())
        }

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        println("MergeMutableSeries through-compaction: r=$r jitter=${"%.1f".format(jitter)}ns")

        // Compaction is O(n log n) — produces latency spikes every 1024 items
        Assertions.assertTrue(r >= 0.97, "MergeMutableSeries through-compaction r=$r < 0.97")
    }

    // ── JournalSeries ────────────────────────────────────────────────────────

    @Test
    fun `JournalSeries wall clock covariance`() {
        val j = JournalSeries<PointcutEvent>()
        repeat(10_000) { j.add(PointcutEvent.random(it)) }
        j.commit()
        j.clear()

        val captured = mutableListOf<PointcutEvent>()
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            j.add(captured.last())
        }

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        val lat = latencyStats(captured.toSeries())
        println("JournalSeries: r=$r jitter=${"%.1f".format(jitter)}ns $lat")

        Assertions.assertTrue(r >= 0.90, "JournalSeries r=$r < 0.90")
        Assertions.assertTrue(jitter < 2_000_000.0, "JournalSeries jitter ${"%.1f".format(jitter)}ns > 2ms")
    }

    @Test
    fun `JournalSeries commit covariance`() {
        val j = JournalSeries<PointcutEvent>()
        // Warmup: prime JIT + fill COW layers
        repeat(5_000) { j.add(PointcutEvent.random(it)) }
        j.commit()
        j.clear()

        val captured = mutableListOf<PointcutEvent>()
        repeat(5_000) { i ->
            captured.add(PointcutEvent.random(i))
            j.add(captured.last())
        }
        j.commit()

        val r = pearsonR(captured.toSeries())
        println("JournalSeries commit: r=$r pending=${j.pendingCount}")

        Assertions.assertEquals(0, j.pendingCount, "pendingCount should be 0 after commit")
        Assertions.assertTrue(r >= 0.90, "JournalSeries commit r=$r < 0.90")
    }

    // ── ChunkedMutableSeries ─────────────────────────────────────────────────

    @Test
    fun `ChunkedMutableSeries wall clock covariance`() {
        val c = ChunkedMutableSeries<PointcutEvent>(chunkSize = 4096)
        repeat(10_000) { c.add(PointcutEvent.random(it)) }
        c.clear()

        val captured = mutableListOf<PointcutEvent>()
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            c.add(captured.last())
        }

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        val lat = latencyStats(captured.toSeries())
        println("ChunkedMutableSeries: r=$r jitter=${"%.1f".format(jitter)}ns $lat")

        Assertions.assertTrue(r >= 0.99, "ChunkedMutableSeries r=$r < 0.99")
        Assertions.assertTrue(jitter < 1_000_000.0, "ChunkedMutableSeries jitter ${"%.1f".format(jitter)}ns > 1ms")
    }

    // ── 3-stage pipeline: Ring → Merge → Journal ─────────────────────────────

    @Test
    fun `pipeline RingMergeJournal covariance`() {
        val ring = RingSeries<PointcutEvent>(65536)
        val merge = MergeMutableSeries<PointcutEvent>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val journal = JournalSeries<PointcutEvent>()

        // Warmup the full pipeline
        repeat(10_000) { i ->
            ring.add(PointcutEvent.random(i))
            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring.b(it)) }
                ring.clear()
                merge.flush()
            }
        }
        repeat(merge.a) { journal.add(merge.b(it)) }
        journal.commit()

        val captured = mutableListOf<PointcutEvent>()
        repeat(50_000) { i ->
            captured.add(PointcutEvent.random(i))
            ring.add(captured.last())

            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring.b(it)) }
                ring.clear()
                merge.flush()
            }
        }

        repeat(merge.a) { journal.add(merge.b(it)) }
        journal.commit()

        val r = pearsonR(captured.toSeries())
        val jitter = interArrivalStdev(captured.toSeries())
        val lat = latencyStats(captured.toSeries())
        println("Ring→Merge→Journal pipeline: r=$r jitter=${"%.1f".format(jitter)}ns $lat")

        Assertions.assertTrue(r >= 0.92, "Pipeline r=$r < 0.92")
        Assertions.assertTrue(jitter < 3_000_000.0, "Pipeline jitter ${"%.1f".format(jitter)}ns > 3ms")
    }

    // ── Cross-type snapshot ───────────────────────────────────────────────────

    @Test
    fun `all types covariance snapshot`() {
        val types = listOf(
            "RingSeries" to { RingSeries<PointcutEvent>(65536) },
            "MergeMutableSeries" to { MergeMutableSeries<PointcutEvent>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) } },
            "JournalSeries" to { JournalSeries<PointcutEvent>() },
            "ChunkedMutableSeries" to { ChunkedMutableSeries<PointcutEvent>(chunkSize = 4096) },
        )

        for ((name, factory) in types) {
            val series = factory()
            val captured = mutableListOf<PointcutEvent>()
            repeat(50_000) { i ->
                captured.add(PointcutEvent.random(i))
                series.add(captured.last())
            }
            val r = pearsonR(captured.toSeries())
            val jitter = interArrivalStdev(captured.toSeries())
            println("$name: r=$r jitter=${"%.1f".format(jitter)}ns")
        }

        Assertions.assertTrue(true) // always passes — diagnostic snapshot
    }
}
