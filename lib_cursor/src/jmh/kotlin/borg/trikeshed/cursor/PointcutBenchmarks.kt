package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.*
import java.util.concurrent.TimeUnit

/**
 * JMH benchmarks for the pointcut → RingSeries → debounce → MutableSeries pipeline.
 *
 * Measures:
 *  - RingSeries O(1) append at various capacities and eviction rates
 *  - MergeMutableSeries batch coalescing and threshold compaction
 *  - RecursiveMutableSeries COW overhead (baseline "don't use at firehose rates")
 *  - JournalSeries mutation journaling + rollback cost
 *  - ChunkedMutableSeries chunked tree append
 *  - Full debounce pipeline: RingSeries drain → MergeMutableSeries → JournalSeries
 *
 * Run with:
 *   ./gradlew jmh
 *
 * Warmup: 3 forks × 3 iterations × 10s (matches JMH best-practice for GC-sensitive benchmarks)
 * Measurement: 5 forks × 5 iterations × 10s
 * GC: STW gc between forks to isolate GC noise
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(
    iterations = 3,
    time = 10,
    timeUnit = TimeUnit.SECONDS,
    batchSize = 1
)
@Measurement(
    iterations = 5,
    time = 10,
    timeUnit = TimeUnit.SECONDS,
    batchSize = 1
)
@Fork(
    value = 3,
    jvmArgs = ["-Xms4g", "-Xmx4g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"]
)
@Threads(1)
@Timeout(time = 30, timeUnit = TimeUnit.MINUTES)
open class PointcutBenchmarks {

    // ── Event record (same wire format as pointcut emitter) ───────────

    data class EventRecord(
        val cls: String,
        val addr: Long,
        val opcode: Int,
        val nano: Long
    )

    private val opcodes = listOf(
        "LOAD", "STORE", "BRANCH", "CALL", "RETURN", "ADD", "SUB", "MULT",
        "DIV", "AND", "OR", "XOR", "SHIFT_L", "SHIFT_R", "CBRANCH", "LABEL"
    )

    private fun randomEvent(): EventRecord {
        return EventRecord(
            cls = "Lorg/xvm/runtime/Frame;",
            addr = (Math.random() * 0x10000).toLong(),
            opcode = (Math.random() * opcodes.size).toInt(),
            nano = java.lang.System.nanoTime()
        )
    }

    // ── RingSeries benchmarks ─────────────────────────────────────────────────

    @Benchmark
    fun ringAppend_1k(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(1024)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_4k(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(4096)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_16k(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(16384)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_64k(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(65536)
        repeat(100_000) { bh.consume(ring.add(randomEvent())) }
    }

    @Benchmark
    fun ringAppend_withEviction(bh: Blackhole) {
        var evicted = 0
        val ring = RingSeries<EventRecord>(256) { evicted++ }
        repeat(100_000) { ring.add(randomEvent()) }
        bh.consume(evicted)
    }

    @Benchmark
    fun ringAppend_1k_readEveryGet(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(1024)
        repeat(100_000) {
            ring.add(randomEvent())
            // Periodically read to force the lambda invoke and bounds check
            if (it % 100 == 0) bh.consume(ring[it % 1024])
        }
    }

    // ── MergeMutableSeries benchmarks ────────────────────────────────────────

    @Benchmark
    fun mergeMutable_appendBelowThreshold(bh: Blackhole) {
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        // Add 63 items — stays below threshold, no compaction triggered
        repeat(63) { merge.add(randomEvent()) }
        bh.consume(merge.a)
    }

    @Benchmark
    fun mergeMutable_appendThroughThreshold(bh: Blackhole) {
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        // Add 128 items — 2 compactions at thresholds 64 and 128
        repeat(128) { merge.add(randomEvent()) }
        bh.consume(merge.a)
    }

    @Benchmark
    fun mergeMutable_flush(bh: Blackhole) {
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 1024) { a, b -> a.opcode.compareTo(b.opcode) }
        repeat(4096) { merge.add(randomEvent()) }
        merge.flush()
        bh.consume(merge.a)
    }

    // ── RecursiveMutableSeries (COW) benchmarks ────────────────────────────────

    @Benchmark
    fun recursiveCOW_append10(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(10) { s.add(randomEvent()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun recursiveCOW_append1k(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(1000) { s.add(randomEvent()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun recursiveCOW_append10k(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(10_000) { s.add(randomEvent()) }
        bh.consume(s.a)
    }

    @Benchmark
    fun recursiveCOW_setThrough10k(bh: Blackhole) {
        val s = RecursiveMutableSeries.create<EventRecord>()
        repeat(5000) { s.add(randomEvent()) }
        // Set every element, causing COW clone each time
        repeat(5000) { s.set(it % 5000, randomEvent()) }
        bh.consume(s.a)
    }

    // ── JournalSeries benchmarks ───────────────────────────────────────────────

    @Benchmark
    fun journal_append10_commit(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(10) { j.add(randomEvent()) }
        j.commit()
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_append1k_rollback(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(randomEvent()) }
        j.rollback()
        bh.consume(j.a)
    }

    @Benchmark
    fun journal_append1k_commit(bh: Blackhole) {
        val j = JournalSeries<EventRecord>()
        repeat(1000) { j.add(randomEvent()) }
        j.commit()
        bh.consume(j.a)
    }

    // ── ChunkedMutableSeries benchmarks ────────────────────────────────────────

    @Benchmark
    fun chunkedAppend_1k(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(1000) { c.add(randomEvent()) }
        bh.consume(c.a)
    }

    @Benchmark
    fun chunkedAppend_10k(bh: Blackhole) {
        val c = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        repeat(10_000) { c.add(randomEvent()) }
        bh.consume(c.a)
    }

    // ── Full debounce pipeline benchmarks ──────────────────────────────────────

    /**
     * Simulates the pointcut→RingSeries→drain→MergeMutableSeries pipeline.
     * RingSeries absorbs firehose (100k events/sec simulated), then drains
     * in batches to MergeMutableSeries which compacts at threshold=64.
     */
    @Benchmark
    fun pipeline_debounce_firehose(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(65536)
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }

        repeat(100_000) { i ->
            ring.add(randomEvent())
            // Simulate drain every 64 events (flush interval)
            if (i > 0 && i % 64 == 0) {
                // Transfer batch from ring to merge
                repeat(ring.a) { merge.add(ring[it]) }
                ring.clear()
            }
        }
        // Final drain
        repeat(ring.a) { merge.add(ring[it]) }
        merge.flush()
        bh.consume(merge.a)
    }

    /**
     * Full 3-stage pipeline: RingSeries → MergeMutableSeries → JournalSeries.
     * Simulates the full ReduxMutableSeries chain.
     */
    @Benchmark
    fun pipeline_full_3stage(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(65536)
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val journal = JournalSeries<EventRecord>()

        repeat(10_000) { i ->
            ring.add(randomEvent())
            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring[it]) }
                ring.clear()
                merge.flush()
            }
        }
        // Journal the merge result
        repeat(merge.a) { journal.add(merge[it]) }
        journal.commit()
        bh.consume(journal.a)
    }

    /**
     * Full pipeline with rollback — measures cost of journaling the full batch.
     */
    @Benchmark
    fun pipeline_full_withRollback(bh: Blackhole) {
        val ring = RingSeries<EventRecord>(65536)
        val merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val journal = JournalSeries<EventRecord>()

        repeat(10_000) { i ->
            ring.add(randomEvent())
            if (i > 0 && i % 64 == 0) {
                repeat(ring.a) { merge.add(ring[it]) }
                ring.clear()
                merge.flush()
            }
        }
        // Journal but then rollback — measures journal overhead independent of storage
        repeat(merge.a) { journal.add(merge[it]) }
        journal.rollback()
        bh.consume(journal.a)
    }

    // ── Redux 5-layer burrito delegate chain ────────────────────────────────────

    /**
     * Redux 5-layer burrito — full delegation chain:
     * Layer 1: RingSeries (firehose absorption, O(1) append)
     * Layer 2: ChunkedMutableSeries (chunked tree, amortized O(1) append)
     * Layer 3: MergeMutableSeries (batch coalescing at threshold)
     * Layer 4: JournalSeries (mutation journal, rollback/commit)
     * Layer 5: RecursiveMutableSeries (versioned COW for kernel snapshots)
     *
     * This is the "right" architecture for the pointcut pipeline.
     * Each layer delegates to the next, adding one concern.
     */
    @Benchmark
    fun burrito_5layer_firehose(bh: Blackhole) {
        // Build the burrito from outside in (layer 5 outermost)
        val layer5_rms = RecursiveMutableSeries.create<EventRecord>()
        val layer4_journal = JournalSeries<PointcutBenchmarks.EventRecord>()
        val layer3_merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val layer2_chunked = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        val layer1_ring = RingSeries<EventRecord>(65536)

        // Simulate 50k firehose events
        repeat(50_000) { i ->
            layer1_ring.add(randomEvent())

            // Drain ring to chunked every 256 events
            if (i > 0 && i % 256 == 0) {
                repeat(layer1_ring.a) { layer2_chunked.add(layer1_ring[it]) }
                layer1_ring.clear()

                // When chunked reaches threshold, merge into merge
                if (i % 4096 == 0) {
                    repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
                    layer2_chunked.clear()
                    layer3_merge.flush()
                }
            }
        }

        // Final flush through all layers
        if (layer2_chunked.a > 0) {
            repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
            layer2_chunked.clear()
            layer3_merge.flush()
        }

        // Journal the merged result into layer 4
        repeat(layer3_merge.a) { layer4_journal.add(layer3_merge[it]) }
        layer4_journal.commit()

        bh.consume(layer4_journal.a)
    }

    /**
     * 5-layer burrito with rollback — measures journal overhead at full scale.
     */
    @Benchmark
    fun burrito_5layer_withRollback(bh: Blackhole) {
        val layer5_rms = RecursiveMutableSeries.create<EventRecord>()
        val layer4_journal = JournalSeries<PointcutBenchmarks.EventRecord>()
        val layer3_merge = MergeMutableSeries<EventRecord>(mergeThreshold = 64) { a, b -> a.opcode.compareTo(b.opcode) }
        val layer2_chunked = ChunkedMutableSeries<EventRecord>(chunkSize = 4096)
        val layer1_ring = RingSeries<EventRecord>(65536)

        repeat(50_000) { i ->
            layer1_ring.add(randomEvent())
            if (i > 0 && i % 256 == 0) {
                repeat(layer1_ring.a) { layer2_chunked.add(layer1_ring[it]) }
                layer1_ring.clear()
                if (i % 4096 == 0) {
                    repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
                    layer2_chunked.clear()
                    layer3_merge.flush()
                }
            }
        }
        if (layer2_chunked.a > 0) {
            repeat(layer2_chunked.a) { layer3_merge.add(layer2_chunked[it]) }
            layer2_chunked.clear()
            layer3_merge.flush()
        }
        repeat(layer3_merge.a) { layer4_journal.add(layer3_merge[it]) }
        layer4_journal.rollback()
        bh.consume(layer4_journal.a)
    }

    // ── Comparison: RingSeries vs COW at firehose ──────────────────────────────

    @Benchmark
    fun comparison_ring_vs_recursive_cow(bh: Blackhole) {
        // Ring: O(1) append, no allocation except on eviction
        val ring = RingSeries<EventRecord>(65536)
        repeat(100_000) { ring.add(randomEvent()) }

        // RecursiveMutableSeries (COW): O(n) clone on every add
        val cow = RecursiveMutableSeries.create<EventRecord>()
        repeat(100_000) { cow.add(randomEvent()) }

        bh.consume(ring.a)
        bh.consume(cow.a)
    }
}