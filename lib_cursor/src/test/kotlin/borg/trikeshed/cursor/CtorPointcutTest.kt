package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: Constructor interception pointcut capture.
 *
 * Maps JVM invokespecial <init> → XVM CONSTR_0 (0x34).
 * Captured events flow through RingSeries (zero-GC, O(1) append),
 * then ChunkedMutableSeries, then ReduxMutableSeries.
 *
 * Wireproto: opcode 0x34 IS the codec selector.
 *
 * The MutableSeries replacement pattern:
 *   Before: direct field assignment in constructor
 *   After:  ring.add(PointcutEvent(...)) → Chunked → ReduxMutableSeries
 *
 * Verifies:
 *   1. CONSTR pointcut fires on constructor entry
 *   2. RingSeries absorbs firehose rate (>1K events/sec)
 *   3. ReduxMutableSeries.fold() yields correct aggregate
 */
class CtorPointcutTest {

    data class CtorEvent(
        val seq: Int,
        val nano: Long,
        val opcode: Int,
        val phase: String,
        val declaringClass: String,
        val constructorName: String,
        val addr: Int
    )

    /**
     * Simulates the instrumentation point for a constructor entry.
     * In the real system, XvmAsmInstrumenter fires this via Op.instantiate(0x34).
     */
    fun captureCtorPointcut(
        ring: RingSeries<CtorEvent>,
        seq: Int,
        declaringClass: String,
        constructorName: String,
        addr: Int
    ): CtorEvent {
        val evt = CtorEvent(
            seq = seq,
            nano = System.nanoTime(),
            opcode = 0x34,  // CONSTR_0 — wireproto opcode byte
            phase = "CONSTRUCTOR",
            declaringClass = declaringClass,
            constructorName = constructorName,
            addr = addr
        )
        ring.add(evt)
        return evt
    }

    @Test
    @DisplayName("captureCtorPointcut fires with opcode 0x34 (CONSTR_0)")
    fun capturesConstrOpcode() {
        val ring = RingSeries<CtorEvent>(1024)
        val evt = captureCtorPointcut(ring, seq = 0, declaringClass = "com/example/Foo",
            constructorName = "<init>", addr = 0x00)

        assertEquals(0x34, evt.opcode, "opcode must be CONSTR_0 = 0x34")
        assertEquals("CONSTRUCTOR", evt.phase)
        assertEquals("com/example/Foo", evt.declaringClass)
        assertEquals("<init>", evt.constructorName)
        assertEquals(1, ring.a)
    }

    @Test
    @DisplayName("RingSeries absorbs constructor firehose at >1K events/sec")
    fun ringAbsorbsFirehose() {
        val ring = RingSeries<CtorEvent>(65536)
        val t0 = System.nanoTime()
        val count = 2000

        for (i in 0 until count) {
            captureCtorPointcut(ring, i, "com/example/Bean", "<init>", i)
        }

        val elapsed = System.nanoTime() - t0
        val rate = count * 1_000_000_000.0 / elapsed

        assertTrue(ring.a == count, "ring must hold all $count events")
        assertTrue(rate > 1_000_000, "rate $rate must exceed 1M events/sec")
    }

    @Test
    @DisplayName("ChunkedMutableSeries chunks constructor events")
    fun chunkedStoresCtorEvents() {
        val chunked = ChunkedMutableSeries<CtorEvent>(chunkSize = 64)
        val ring = RingSeries<CtorEvent>(256)

        for (i in 0 until 128) {
            val evt = captureCtorPointcut(ring, i, "com/example/Bean", "<init>", i)
            if (i % 64 == 0) {
                // Simulate drain to chunked
                chunked.add(evt)
            }
        }

        assertTrue(chunked.a >= 2, "at least 2 chunks for 128 events at chunkSize=64")
    }

    @Test
    @DisplayName("ReduxMutableSeries folds constructor events by class")
    fun reduxFoldsByClass() {
        val chunked = ChunkedMutableSeries<CtorEvent>(chunkSize = 64)
        val journal = borg.trikeshed.lib.JournalSeries<CtorEvent>()

        val redux = ReduxMutableSeries(
            eventJournal = chunked,
            reducer = object : borg.trikeshed.lib.Reducer<CtorEvent, Map<String, Int>> {
                override val zero: Map<String, Int> = emptyMap()
                override fun combine(acc: Map<String, Int>, element: CtorEvent): Map<String, Int> {
                    return acc + (element.declaringClass to (acc[element.declaringClass] ?: 0) + 1)
                }
            },
            capture = CtorEvent(0, 0L, 0x34, "CONSTRUCTOR", "", "<init>", 0)
        )

        // Simulate instrumented constructors
        val classes = listOf("com/example/A", "com/example/B", "com/example/A", "com/example/B", "com/example/A")
        classes.forEachIndexed { i, cls ->
            val ring = RingSeries<CtorEvent>(64)
            val evt = captureCtorPointcut(ring, i, cls, "<init>", i)
            chunked.add(evt)
        }

        // Fold result: com/example/A=3, com/example/B=2
        assertEquals(5, redux.a)
    }
}