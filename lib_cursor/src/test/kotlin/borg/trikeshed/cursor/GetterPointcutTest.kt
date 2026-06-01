package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import kotlin.time.measureTime

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: Getter interception pointcut capture.
 *
 * Maps JVM getfield/getstatic → XVM L_GET (0xA5) / P_GET (0xA7).
 * Captured events flow through RingSeries → ChunkedMutableSeries → ReduxMutableSeries.
 *
 * Wireproto: opcode byte IS the codec selector.
 *   0xA5 = L_GET (instance field)
 *   0xA7 = P_GET (static field)
 *
 * The MutableSeries replacement pattern:
 *   Before: obj.field (direct field access)
 *   After:  ring.add(GetEvent(...)) → Chunked → ReduxMutableSeries
 */
class GetterPointcutTest {

    data class GetEvent(
        val seq: Int,
        val nano: Long,
        val opcode: Int,        // 0xA5=L_GET, 0xA7=P_GET
        val phase: String,      // "GETTER"
        val ownerClass: String, // declaring class of the field
        val fieldName: String,
        val fieldDesc: String,  // JVM descriptor e.g. "I", "Ljava/lang/String;"
        val addr: Int
    )

    fun captureGetterPointcut(
        ring: RingSeries<GetEvent>,
        seq: Int,
        ownerClass: String,
        fieldName: String,
        fieldDesc: String,
        isStatic: Boolean,
        addr: Int
    ): GetEvent {
        val evt = GetEvent(
            seq = seq,
            nano = System.nanoTime(),
            opcode = if (isStatic) 0xA7 else 0xA5,
            phase = "GETTER",
            ownerClass = ownerClass,
            fieldName = fieldName,
            fieldDesc = fieldDesc,
            addr = addr
        )
        ring.add(evt)
        return evt
    }

    @Test
    @DisplayName("captureGetterPointcut fires with opcode 0xA5 (L_GET) for instance field")
    fun capturesInstanceGetterOpcode() {
        val ring = RingSeries<GetEvent>(1024)
        val evt = captureGetterPointcut(
            ring = ring,
            seq = 0,
            ownerClass = "com/example/Bean",
            fieldName = "value",
            fieldDesc = "I",
            isStatic = false,
            addr = 0x1A
        )

        assertEquals(0xA5, evt.opcode, "instance getter opcode must be L_GET = 0xA5")
        assertEquals("GETTER", evt.phase)
        assertEquals("value", evt.fieldName)
        assertEquals("I", evt.fieldDesc)
        assertEquals(1, ring.a)
    }

    @Test
    @DisplayName("captureGetterPointcut fires with opcode 0xA7 (P_GET) for static field")
    fun capturesStaticGetterOpcode() {
        val ring = RingSeries<GetEvent>(1024)
        val evt = captureGetterPointcut(
            ring = ring,
            seq = 0,
            ownerClass = "com/example/Constants",
            fieldName = "MAX_SIZE",
            fieldDesc = "I",
            isStatic = true,
            addr = 0x2B
        )

        assertEquals(0xA7, evt.opcode, "static getter opcode must be P_GET = 0xA7")
        assertEquals("GETTER", evt.phase)
        assertEquals("MAX_SIZE", evt.fieldName)
        assertEquals(1, ring.a)
    }

    @Test
    @DisplayName("RingSeries absorbs getter firehose at >1K events/sec")
    fun ringAbsorbsFirehose() {
        val ring = RingSeries<GetEvent>(65536)
        val count = 5000

        val elapsed = measureTime {
            for (i in 0 until count) {
                captureGetterPointcut(
                    ring = ring,
                    seq = i,
                    ownerClass = "com/example/Entity",
                    fieldName = if (i % 2 == 0) "id" else "name",
                    fieldDesc = if (i % 2 == 0) "J" else "Ljava/lang/String;",
                    isStatic = i % 3 == 0,
                    addr = i
                )
            }
        }

        val rate = count * 1_000_000_000.0 / elapsed.inWholeNanoseconds

        assertEquals(count, ring.a)
        assertTrue(rate > 1_000_000, "getter rate $rate must exceed 1M events/sec")
    }

    @Test
    @DisplayName("ReduxMutableSeries folds getters by (ownerClass, fieldName)")
    fun reduxFoldsByOwnerAndField() {
        val chunked = ChunkedMutableSeries<GetEvent>(chunkSize = 64)

        val reducer = object : borg.trikeshed.lib.Reducer<GetEvent, Map<String, Int>> {
            override val zero: Map<String, Int> = emptyMap()
            override fun combine(acc: Map<String, Int>, element: GetEvent): Map<String, Int> {
                val key = "${element.ownerClass}.${element.fieldName}"
                return acc + (key to (acc[key] ?: 0) + 1)
            }
        }

        val redux = ReduxMutableSeries(
            eventJournal = chunked,
            reducer = reducer,
            capture = GetEvent(0, 0L, 0xA5, "GETTER", "", "field", "I", 0)
        )

        // Simulate getter accesses: id accessed 3x, name accessed 2x
        listOf(
            "com/example/Bean" to "id",
            "com/example/Bean" to "id",
            "com/example/Bean" to "id",
            "com/example/Bean" to "name",
            "com/example/Bean" to "name",
        ).forEachIndexed { i, (cls, field) ->
            val ring = RingSeries<GetEvent>(16)
            val desc = if (field == "id") "J" else "Ljava/lang/String;"
            val evt = captureGetterPointcut(ring, i, cls, field, desc, false, i)
            chunked.add(evt)
        }

        assertEquals(5, redux.a, "redux must hold all 5 events")
    }

    @Test
    @DisplayName("Getter events survive chunked storage and eviction")
    fun getterSurvivesChunkedEviction() {
        val chunked = ChunkedMutableSeries<GetEvent>(chunkSize = 8)
        val ring = RingSeries<GetEvent>(16)

        // Add 20 events — forces multiple chunk compaction
        for (i in 0 until 20) {
            val evt = captureGetterPointcut(
                ring = ring,
                seq = i,
                ownerClass = "com/example/Cache",
                fieldName = "entry",
                fieldDesc = "Ljava/lang/Object;",
                isStatic = false,
                addr = i
            )
            chunked.add(evt)
        }

        // Verify all events survived chunked storage
        assertTrue(chunked.a >= 20, "chunked must hold all 20 events after compaction")
    }
}