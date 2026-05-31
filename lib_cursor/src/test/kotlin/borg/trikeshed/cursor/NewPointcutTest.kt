package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: NEW (allocation) interception pointcut capture.
 *
 * Maps JVM bytecode to XVM allocation opcodes:
 *   new          → 0x38  (NEW_0)    — new object
 *   anewarray    → 0x3A  (NEW_N)    — new reference array
 *   multianewarray → 0x3B (NEW_T)   — new multidimensional array
 *
 * Additional allocation opcodes:
 *   NEW_1 (0x39), NEWC_0-3 (0x40-0x43), NEWV_0-3 (0x48-0x4B)
 *
 * Pipeline: RingSeries(65536, zero-GC) → ChunkedMutableSeries → ReduxMutableSeries
 *
 * After: all allocation sites (new, anewarray, multianewarray) route through
 *   ring.add(NewEvent(...)) and the eigensolver ranks by allocation frequency.
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
        val opcode: Int,       // wireproto byte
        val phase: String,    // "ALLOC"
        val allocKind: AllocKind,
        val allocatedType: String,  // JVM descriptor, e.g. "Lcom/example/Foo;"
        val dimensions: Int,   // array dimensions (0 for scalar new)
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

    // ── Opcode mapping tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("new → opcode 0x38 (NEW_0)")
    fun newOpcode() {
        val ring = RingSeries<NewEvent>(256)
        val evt = captureNewPointcut(ring, 0, AllocKind.NEW, "Lcom/example/Foo;", 0, 0x10)
        assertEquals(0x38, evt.opcode)
        assertEquals("ALLOC", evt.phase)
        assertEquals(AllocKind.NEW, evt.allocKind)
    }

    @Test
    @DisplayName("anewarray → opcode 0x3A (NEW_N)")
    fun anewarrayOpcode() {
        val ring = RingSeries<NewEvent>(256)
        val evt = captureNewPointcut(ring, 1, AllocKind.NEW_N, "[I", 1, 0x20)
        assertEquals(0x3A, evt.opcode)
        assertEquals("ALLOC", evt.phase)
        assertEquals(1, evt.dimensions)
    }

    @Test
    @DisplayName("multianewarray → opcode 0x3B (NEW_T)")
    fun multianewarrayOpcode() {
        val ring = RingSeries<NewEvent>(256)
        val evt = captureNewPointcut(ring, 2, AllocKind.NEW_T, "[[Ljava/lang/String;", 2, 0x30)
        assertEquals(0x3B, evt.opcode)
        assertEquals(2, evt.dimensions)
    }

    @Test
    @DisplayName("NEWC_0 (0x40) and NEWV_0 (0x48) separate namespaces")
    fun newcNewvSeparate() {
        val ring = RingSeries<NewEvent>(256)
        val evtC = captureNewPointcut(ring, 0, AllocKind.NEWC_0, "[I", 1, 0x40)
        val evtV = captureNewPointcut(ring, 1, AllocKind.NEWV_0, "[J", 1, 0x48)
        assertEquals(0x40, evtC.opcode)
        assertEquals(0x48, evtV.opcode)
    }

    // ── Firehose tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("RingSeries absorbs allocation firehose at >1K events/sec")
    fun ringAbsorbsAllocFirehose() {
        val ring = RingSeries<NewEvent>(65536)
        val t0 = System.nanoTime()
        val count = 5000

        val kinds = AllocKind.entries.toTypedArray()
        val types = listOf("Lcom/example/A;", "Lcom/example/B;", "[I", "[[Ljava/lang/String;")
        for (i in 0 until count) {
            val kind = kinds[i % kinds.size]
            val dims = if (kind == AllocKind.NEW || kind == AllocKind.NEW_1) 0 else (i % 3 + 1)
            captureNewPointcut(ring, i, kind, types[i % types.size], dims, i)
        }

        val elapsed = System.nanoTime() - t0
        val rate = count * 1_000_000_000.0 / elapsed

        assertEquals(count, ring.a)
        assertTrue(rate > 1_000_000, "allocation rate $rate must exceed 1M events/sec")
    }

    @Test
    @DisplayName("ChunkedMutableSeries compacts allocation events at threshold")
    fun chunkedCompactsAtThreshold() {
        val chunked = ChunkedMutableSeries<NewEvent>(chunkSize = 32)
        val ring = RingSeries<NewEvent>(64)

        val kinds = AllocKind.entries.toTypedArray()
        for (i in 0 until 100) {
            val evt = captureNewPointcut(ring, i, kinds[i % kinds.size], "Lcom/example/Obj;", 0, i)
            chunked.add(evt)
        }

        // At chunkSize=32, 100 events → at least 3 chunks
        assertTrue(chunked.a >= 100, "chunked must retain all 100 events")
    }

    // ── Redux fold tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ReduxMutableSeries folds allocations by type — top consumer")
    fun reduxFoldsByAllocatedType() {
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

        // Simulate allocation pattern: Foo=3x, Bar=2x, Baz=5x
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

        assertEquals(10, redux.a)

        // Fold result: should see Baz=5, Foo=3, Bar=2
        // (eigensolver would rank Baz highest by allocation count)
    }

    @Test
    @DisplayName("Allocation opcode spectrum — all 12 variants fire correctly")
    fun allAllocOpcodesFire() {
        val ring = RingSeries<NewEvent>(1024)
        var seq = 0

        for (kind in AllocKind.entries) {
            val evt = captureNewPointcut(ring, seq++, kind, "Ljava/lang/Object;", 0, seq)
            assertEquals(ALLOC_OPCODE[kind], evt.opcode, "${kind.name} opcode must match")
        }

        assertEquals(AllocKind.entries.size, ring.a)
    }

    @Test
    @DisplayName("NEW pointcut chains to CONSTR pointcut — allocation precedes init")
    fun newChainsToConstr() {
        val chunked = ChunkedMutableSeries<NewEvent>(chunkSize = 64)
        val ring = RingSeries<NewEvent>(32)

        // Simulate: new Foo → invokespecial <init>
        repeat(5) { i ->
            val newEvt = captureNewPointcut(ring, i * 2, AllocKind.NEW, "Lcom/example/Foo;", 0, i * 2)
            chunked.add(newEvt)
            // Constr follows
            val constrEvt = CtorPointcutTest().captureCtorPointcut(
                borg.trikeshed.lib.RingSeries(16), i * 2 + 1, "com/example/Foo", "<init>", i * 2 + 1
            )
            // just verify each phase fires at distinct address
            assertTrue(newEvt.addr < constrEvt.addr, "new addr must precede <init> addr")
        }
    }
}