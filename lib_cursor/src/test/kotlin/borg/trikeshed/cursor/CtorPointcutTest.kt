package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import kotlin.time.measureTime

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: Constructor interception pointcut capture.
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

    private fun captureCtorPointcut(
        ring: RingSeries<CtorEvent>,
        seq: Int,
        declaringClass: String,
        constructorName: String,
        addr: Int
    ): CtorEvent {
        val evt = CtorEvent(
            seq = seq,
            nano = System.nanoTime(),
            opcode = 0x34,
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
        val t0 = System.nanoTime()
        val ring = RingSeries<CtorEvent>(1024)
        val evt = captureCtorPointcut(ring, seq = 0, declaringClass = "com/example/Foo",
            constructorName = "<init>", addr = 0x00)
        val t1 = System.nanoTime()

        assertEquals(0x34, evt.opcode, "opcode must be CONSTR_0 = 0x34")
        assertEquals("CONSTRUCTOR", evt.phase)
        assertEquals("com/example/Foo", evt.declaringClass)
        assertEquals("<init>", evt.constructorName)
        assertEquals(1, ring.a)
        assertTrue(evt.nano in t0..t1, "nano ${evt.nano} must be within [$t0, $t1]")
    }

    @Test
    @DisplayName("RingSeries absorbs constructor firehose at >1K events/sec")
    fun ringAbsorbsFirehose() {
        val t0 = System.nanoTime()
        val ring = RingSeries<CtorEvent>(65536)
        val count = 2000

        val elapsed = measureTime {
            for (i in 0 until count) {
                captureCtorPointcut(ring, i, "com/example/Bean", "<init>", i)
            }
        }
        val t1 = System.nanoTime()
        val rate = count * 1_000_000_000.0 / elapsed.inWholeNanoseconds

        assertTrue(ring.a == count, "ring must hold all $count events")
        assertTrue(rate > 1_000_000, "rate $rate must exceed 1M events/sec")
        for (i in 0 until count) {
            val evt = ring[i]
            assertTrue(evt.nano in t0..t1, "nano ${evt.nano} must be within [$t0, $t1]")
        }
    }

    @Test
    @DisplayName("ChunkedMutableSeries chunks constructor events")
    fun chunkedStoresCtorEvents() {
        val t0 = System.nanoTime()
        val chunked = ChunkedMutableSeries<CtorEvent>(chunkSize = 64)
        val ring = RingSeries<CtorEvent>(256)

        val list = mutableListOf<CtorEvent>()
        for (i in 0 until 128) {
            val evt = captureCtorPointcut(ring, i, "com/example/Bean", "<init>", i)
            list.add(evt)
            if (i % 64 == 0) {
                chunked.add(evt)
            }
        }
        val t1 = System.nanoTime()

        assertTrue(chunked.a >= 2, "at least 2 chunks for 128 events at chunkSize=64")
        for (i in 0 until chunked.a) {
            assertTrue(chunked[i].nano in t0..t1, "nano must be in bounds")
        }
    }

    @Test
    @DisplayName("ReduxMutableSeries folds constructor events by class")
    fun reduxFoldsByClass() {
        val t0 = System.nanoTime()
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

        val classes = listOf("com/example/A", "com/example/B", "com/example/A", "com/example/B", "com/example/A")
        classes.forEachIndexed { i, cls ->
            val ring = RingSeries<CtorEvent>(64)
            val evt = captureCtorPointcut(ring, i, cls, "<init>", i)
            chunked.add(evt)
        }
        val t1 = System.nanoTime()

        assertEquals(5, redux.a)
        for (i in 0 until chunked.a) {
            assertTrue(chunked[i].nano in t0..t1, "nano must be in bounds")
        }
    }
}