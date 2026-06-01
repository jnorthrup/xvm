package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import kotlin.time.measureTime

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: Setter interception pointcut capture.
 *
 * Maps JVM putfield/putstatic → XVM L_SET (0xA6) / P_SET (0xA8).
 * Captured events flow through RingSeries → ChunkedMutableSeries → ReduxMutableSeries.
 *
 * Wireproto: opcode byte IS the codec selector.
 *   0xA6 = L_SET (instance field)
 *   0xA8 = P_SET (static field)
 *
 * The MutableSeries replacement pattern:
 *   Before: obj.field = value (direct field write)
 *   After:  ring.add(SetEvent(...)) → Chunked → ReduxMutableSeries
 *
 * After: all direct field assignments in constructors/setters delegate to
 *   ring.add(PointcutEvent) and the shim/data split in the MutableSeries chain.
 */
class SetterPointcutTest {

    data class SetEvent(
        val seq: Int,
        val nano: Long,
        val opcode: Int,         // 0xA6=L_SET, 0xA8=P_SET
        val phase: String,       // "SETTER"
        val ownerClass: String,
        val fieldName: String,
        val fieldDesc: String,
        val addr: Int
    )

    fun captureSetterPointcut(
        ring: RingSeries<SetEvent>,
        seq: Int,
        ownerClass: String,
        fieldName: String,
        fieldDesc: String,
        isStatic: Boolean,
        addr: Int
    ): SetEvent {
        val evt = SetEvent(
            seq = seq,
            nano = System.nanoTime(),
            opcode = if (isStatic) 0xA8 else 0xA6,
            phase = "SETTER",
            ownerClass = ownerClass,
            fieldName = fieldName,
            fieldDesc = fieldDesc,
            addr = addr
        )
        ring.add(evt)
        return evt
    }

    @Test
    @DisplayName("captureSetterPointcut fires with opcode 0xA6 (L_SET) for instance field")
    fun capturesInstanceSetterOpcode() {
        val t0 = System.nanoTime()
        val ring = RingSeries<SetEvent>(1024)
        val evt = captureSetterPointcut(
            ring = ring,
            seq = 0,
            ownerClass = "com/example/Bean",
            fieldName = "value",
            fieldDesc = "I",
            isStatic = false,
            addr = 0x1C
        )
        val t1 = System.nanoTime()

        assertEquals(0xA6, evt.opcode, "instance setter opcode must be L_SET = 0xA6")
        assertEquals("SETTER", evt.phase)
        assertEquals("value", evt.fieldName)
        assertEquals("I", evt.fieldDesc)
        assertEquals(1, ring.a)
        assertTrue(evt.nano in t0..t1, "nano ${evt.nano} must be within [$t0, $t1]")
    }

    @Test
    @DisplayName("captureSetterPointcut fires with opcode 0xA8 (P_SET) for static field")
    fun capturesStaticSetterOpcode() {
        val t0 = System.nanoTime()
        val ring = RingSeries<SetEvent>(1024)
        val evt = captureSetterPointcut(
            ring = ring,
            seq = 0,
            ownerClass = "com/example/Constants",
            fieldName = "VERSION",
            fieldDesc = "I",
            isStatic = true,
            addr = 0x3D
        )
        val t1 = System.nanoTime()

        assertEquals(0xA8, evt.opcode, "static setter opcode must be P_SET = 0xA8")
        assertEquals("SETTER", evt.phase)
        assertEquals("VERSION", evt.fieldName)
        assertTrue(evt.nano in t0..t1, "nano ${evt.nano} must be within [$t0, $t1]")
    }

    @Test
    @DisplayName("RingSeries absorbs setter firehose at >1K events/sec")
    fun ringAbsorbsSetterFirehose() {
        val t0 = System.nanoTime()
        val ring = RingSeries<SetEvent>(65536)
        val count = 5000

        val elapsed = measureTime {
            for (i in 0 until count) {
                captureSetterPointcut(
                    ring = ring,
                    seq = i,
                    ownerClass = "com/example/Entity",
                    fieldName = if (i % 2 == 0) "id" else "timestamp",
                    fieldDesc = if (i % 2 == 0) "J" else "J",
                    isStatic = i % 4 == 0,
                    addr = i
                )
            }
        }
        val t1 = System.nanoTime()

        val rate = count * 1_000_000_000.0 / elapsed.inWholeNanoseconds

        assertEquals(count, ring.a)
        assertTrue(rate > 1_000_000, "setter rate $rate must exceed 1M events/sec")
        for (i in 0 until count) {
            assertTrue(ring.b(i).nano in t0..t1, "event $i must be inside bounds")
        }
    }

    @Test
    @DisplayName("ReduxMutableSeries folds setters by (ownerClass, fieldName)")
    fun reduxFoldsSettersByOwnerField() {
        val t0 = System.nanoTime()
        val chunked = ChunkedMutableSeries<SetEvent>(chunkSize = 64)

        val reducer = object : borg.trikeshed.lib.Reducer<SetEvent, Map<String, Int>> {
            override val zero: Map<String, Int> = emptyMap()
            override fun combine(acc: Map<String, Int>, element: SetEvent): Map<String, Int> {
                val key = "${element.ownerClass}.${element.fieldName}"
                return acc + (key to (acc[key] ?: 0) + 1)
            }
        }

        val redux = ReduxMutableSeries(
            eventJournal = chunked,
            reducer = reducer,
            capture = SetEvent(0, System.nanoTime(), 0xA6, "SETTER", "", "field", "I", 0)
        )

        // Simulate setter invocations
        listOf(
            "com/example/Bean" to "value",
            "com/example/Bean" to "value",
            "com/example/Bean" to "enabled",
            "com/example/Bean" to "value",
            "com/example/Bean" to "enabled",
        ).forEachIndexed { i, (cls, field) ->
            val r = RingSeries<SetEvent>(16)
            val desc = if (field == "value") "I" else "Z"
            val evt = captureSetterPointcut(r, i, cls, field, desc, false, i)
            chunked.add(evt)
        }
        val t1 = System.nanoTime()

        assertEquals(5, redux.a)
        for (i in 0 until chunked.a) {
            assertTrue(chunked.b(i).nano in t0..t1, "event $i must be inside bounds")
        }
    }

    @Test
    @DisplayName("Setter events chain into getter events via ReduxMutableSeries")
    fun setterChainsToGetterViaRedux() {
        val t0 = System.nanoTime()
        val chunked = ChunkedMutableSeries<SetEvent>(chunkSize = 64)

        val reducer = object : borg.trikeshed.lib.Reducer<SetEvent, Map<String, Long>> {
            override val zero: Map<String, Long> = emptyMap()
            override fun combine(acc: Map<String, Long>, element: SetEvent): Map<String, Long> {
                val key = "${element.ownerClass}.${element.fieldName}"
                // Stamp with nano — fold-on-read for hot fields
                return acc + (key to element.nano)
            }
        }

        val redux = ReduxMutableSeries(
            eventJournal = chunked,
            reducer = reducer,
            capture = SetEvent(0, System.nanoTime(), 0xA6, "SETTER", "", "field", "I", 0)
        )
        val ring = RingSeries<SetEvent>(32)

        // Simulate a setter chain: Bean.value set 3x
        repeat(3) { i ->
            val evt = captureSetterPointcut(ring, i, "com/example/Bean", "value", "I", false, i)
            chunked.add(evt)
        }
        val t1 = System.nanoTime()

        // Redux fold: last-write-wins for Bean.value
        assertEquals(3, redux.a)
        for (i in 0 until chunked.a) {
            assertTrue(chunked.b(i).nano in t0..t1, "event $i must be inside bounds")
        }
    }
}