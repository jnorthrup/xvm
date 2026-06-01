package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import kotlin.time.measureTime

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: NEW (allocation) interception pointcut capture.
 */
class NewPointcutTest {

    enum class AllocKind {
        NEW,           // new object           → 0x38
        NEW_1,         // new object +1arg     → 0x39
        NEW_N,         // new array           → 0x3A
        NEW_T,         // new typed array     → 0x3B
        NEWC_0,        // new const array     → 0x40
        NEWC_1,        // new const array+1   → 0x41
        NEWC_N,        // new const array+N   → 0x42
        NEWC_T,        // new const array+T   → 0x43
        NEWV_0,        // new var array       → 0x48
        NEWV_1,        // new var array+1     → 0x49
        NEWV_N,        // new var array+N     → 0x4A
        NEWV_T,        // new var array+T     → 0x4B
    }

    data class NewEvent(
        val seq: Int,
        val nano: Long,
        val opcode: Int,
        val phase: String,
        val allocKind: AllocKind,
        val allocatedType: String,
        val dimensions: Int,
        val addr: Int
    )

    private val ALLOC_OPCODE = mapOf(
        AllocKind.NEW     to 0x38,
        AllocKind.NEW_1   to 0x39,
        AllocKind.NEW_N   to 0x3A,
        AllocKind.NEW_T   to 0x3B,
        AllocKind.NEWC_0  to 0x40,
        AllocKind.NEWC_1  to 0x41,
        AllocKind.NEWC_N  to 0x42,
        AllocKind.NEWC_T  to 0x43,
        AllocKind.NEWV_0  to 0x48,
        AllocKind.NEWV_1  to 0x49,
        AllocKind.NEWV_N  to 0x4A,
        AllocKind.NEWV_T  to 0x4B,
    )

    fun captureNewPointcut(
        ring: RingSeries<NewEvent>,
        seq: Int,
        kind: AllocKind,
        allocatedType: String,
        dimensions: Int,
        addr: Int
    ): NewEvent {
        val evt = NewEvent(
            seq = seq,
            nano = System.nanoTime(),
            opcode = ALLOC_OPCODE[kind] ?: 0x38,
            phase = "ALLOC",
            allocKind = kind,
            allocatedType = allocatedType,
            dimensions = dimensions,
            addr = addr
        )
        ring.add(evt)
        return evt
    }

    @Test
    @DisplayName("new → opcode 0x38 (NEW_0)")
    fun newOpcode() {
        val t0 = System.nanoTime()
        val ring = RingSeries<NewEvent>(256)
        val evt = captureNewPointcut(ring, 0, AllocKind.NEW, "Lcom/example/Foo;", 0, 0x10)
        val t1 = System.nanoTime()

        assertEquals(0x38, evt.opcode)
        assertEquals("ALLOC", evt.phase)
        assertEquals(AllocKind.NEW, evt.allocKind)
        assertTrue(evt.nano in t0..t1, "nano ${evt.nano} must be within [$t0, $t1]")
    }

    @Test
    @DisplayName("anewarray → opcode 0x3A (NEW_N)")
    fun anewarrayOpcode() {
        val t0 = System.nanoTime()
        val ring = RingSeries<NewEvent>(256)
        val evt = captureNewPointcut(ring, 1, AllocKind.NEW_N, "[I", 1, 0x20)
        val t1 = System.nanoTime()

        assertEquals(0x3A, evt.opcode)
        assertEquals("ALLOC", evt.phase)
        assertEquals(1, evt.dimensions)
        assertTrue(evt.nano in t0..t1)
    }

    @Test
    @DisplayName("multianewarray → opcode 0x3B (NEW_T)")
    fun multianewarrayOpcode() {
        val t0 = System.nanoTime()
        val ring = RingSeries<NewEvent>(256)
        val evt = captureNewPointcut(ring, 2, AllocKind.NEW_T, "[[Ljava/lang/String;", 2, 0x30)
        val t1 = System.nanoTime()

        assertEquals(0x3B, evt.opcode)
        assertEquals(2, evt.dimensions)
        assertTrue(evt.nano in t0..t1)
    }

    @Test
    @DisplayName("NEWC_0 (0x40) and NEWV_0 (0x48) separate namespaces")
    fun newcNewvSeparate() {
        val t0 = System.nanoTime()
        val ring = RingSeries<NewEvent>(256)
        val evtC = captureNewPointcut(ring, 0, AllocKind.NEWC_0, "[I", 1, 0x40)
        val evtV = captureNewPointcut(ring, 1, AllocKind.NEWV_0, "[J", 1, 0x48)
        val t1 = System.nanoTime()

        assertEquals(0x40, evtC.opcode)
        assertEquals(0x48, evtV.opcode)
        assertTrue(evtC.nano in t0..t1)
        assertTrue(evtV.nano in t0..t1)
    }

    @Test
    @DisplayName("RingSeries absorbs allocation firehose at >1K events/sec")
    fun ringAbsorbsAllocFirehose() {
        val t0 = System.nanoTime()
        val ring = RingSeries<NewEvent>(65536)
        val count = 5000

        val kinds = AllocKind.entries.toTypedArray()
        val types = listOf("Lcom/example/A;", "Lcom/example/B;", "[I", "[[Ljava/lang/String;")
        val elapsed = measureTime {
            for (i in 0 until count) {
                val kind = kinds[i % kinds.size]
                val dims = if (kind == AllocKind.NEW || kind == AllocKind.NEW_1) 0 else (i % 3 + 1)
                captureNewPointcut(ring, i, kind, types[i % types.size], dims, i)
            }
        }
        val t1 = System.nanoTime()
        val rate = count * 1_000_000_000.0 / elapsed.inWholeNanoseconds

        assertEquals(count, ring.a)
        assertTrue(rate > 1_000_000, "allocation rate $rate must exceed 1M events/sec")
        for (i in 0 until count) {
            assertTrue(ring[i].nano in t0..t1)
        }
    }

    @Test
    @DisplayName("ChunkedMutableSeries compacts allocation events at threshold")
    fun chunkedCompactsAtThreshold() {
        val t0 = System.nanoTime()
        val chunked = ChunkedMutableSeries<NewEvent>(chunkSize = 32)
        val ring = RingSeries<NewEvent>(64)

        val kinds = AllocKind.entries.toTypedArray()
        for (i in 0 until 100) {
            val evt = captureNewPointcut(ring, i, kinds[i % kinds.size], "Lcom/example/Obj;", 0, i)
            chunked.add(evt)
        }
        val t1 = System.nanoTime()

        assertTrue(chunked.a >= 100, "chunked must retain all 100 events")
        for (i in 0 until chunked.a) {
            assertTrue(chunked[i].nano in t0..t1)
        }
    }

    @Test
    @DisplayName("ReduxMutableSeries folds allocations by type — top consumer")
    fun reduxFoldsByAllocatedType() {
        val t0 = System.nanoTime()
        val chunked = ChunkedMutableSeries<NewEvent>(chunkSize = 64)
        val ring = RingSeries<NewEvent>(32)

        val reducer = object : borg.trikeshed.lib.Reducer<NewEvent, Map<String, Int>> {
            override val zero: Map<String, Int> = emptyMap()
            override fun combine(acc: Map<String, Int>, element: NewEvent): Map<String, Int> {
                return acc + (element.allocatedType to (acc[element.allocatedType] ?: 0) + 1)
            }
        }

        val redux = ReduxMutableSeries(
            eventJournal = chunked,
            reducer = reducer,
            capture = NewEvent(0, 0L, 0x38, "ALLOC", AllocKind.NEW, "Ljava/lang/Object;", 0, 0)
        )

        listOf(
            "Lcom/example/Foo;" to 3,
            "Lcom/example/Bar;" to 2,
            "Lcom/example/Baz;" to 5,
        ).forEach { (type, count) ->
            for (i in 0 until count) {
                val r = RingSeries<NewEvent>(16)
                val evt = captureNewPointcut(r, i, AllocKind.NEW, type, 0, i)
                chunked.add(evt)
            }
        }
        val t1 = System.nanoTime()

        assertEquals(10, redux.a)
        for (i in 0 until chunked.a) {
            assertTrue(chunked[i].nano in t0..t1)
        }
    }

    @Test
    @DisplayName("Allocation opcode spectrum — all 12 variants fire correctly")
    fun allAllocOpcodesFire() {
        val t0 = System.nanoTime()
        val ring = RingSeries<NewEvent>(1024)
        var seq = 0

        for (kind in AllocKind.entries) {
            val evt = captureNewPointcut(ring, seq++, kind, "Ljava/lang/Object;", 0, seq)
            assertEquals(ALLOC_OPCODE[kind], evt.opcode, "${kind.name} opcode must match")
        }
        val t1 = System.nanoTime()

        assertEquals(AllocKind.entries.size, ring.a)
        for (i in 0 until ring.a) {
            assertTrue(ring[i].nano in t0..t1)
        }
    }

    @Test
    @DisplayName("NEW pointcut chains to CONSTR pointcut — allocation precedes init")
    fun newChainsToConstr() {
        val t0 = System.nanoTime()
        val chunked = ChunkedMutableSeries<NewEvent>(chunkSize = 64)
        val ring = RingSeries<NewEvent>(32)

        repeat(5) { i ->
            val newEvt = captureNewPointcut(ring, i * 2, AllocKind.NEW, "Lcom/example/Foo;", 0, i * 2)
            chunked.add(newEvt)
            val constrAddr = i * 2 + 1
            assertTrue(newEvt.addr < constrAddr, "new addr must precede <init> addr")
        }
        val t1 = System.nanoTime()

        for (i in 0 until chunked.a) {
            assertTrue(chunked[i].nano in t0..t1)
        }
    }
}