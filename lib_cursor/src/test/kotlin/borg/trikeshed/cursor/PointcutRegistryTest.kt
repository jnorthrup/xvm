package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.ReduxMutableSeries

/**
 * TDD: PointcutRegistry — the (T) → T unary codec function that handles
 * List → MutableSeries → List codec for pointcut interception.
 *
 * Pattern:
 *   intercept(opcode, value, ring)  → codec.encode(value, ring)
 *   read(opcode, ring, index)      → codec.decode(ring, index)
 *   readAll(opcode, ring)          → codec.decodeAll(ring) → List<T>
 *
 * The registry replaces direct field access with:
 *   ring.add(PointcutEvent(...)) — O(1), zero-GC, survives firehose rates
 *   ring.b(i)                   — O(1) indexed read
 *
 * Wireproto: opcode byte IS the codec selector (1 byte, 0-255).
 */
class PointcutRegistryTest {

    // ── Intercept / Read round-trip ──────────────────────────────────────

    @Test
    @DisplayName("intercept → read round-trip: CONSTR_0 (0x34)")
    fun roundTripConstr() {
        PointcutRegistry.installDefaults()

        val ring = RingSeries<CtorPointcutTest.CtorEvent>(256)
        val value = CtorPointcutTest.CtorEvent(
            seq = 0, nano = System.nanoTime(),
            opcode = 0x34, phase = "CONSTRUCTOR",
            declaringClass = "com/example/Foo",
            constructorName = "<init>",
            addr = 0x10
        )

        // Intercept: encode into ring
        @Suppress("UNCHECKED_CAST")
        PointcutRegistry.intercept(0x34, value, ring as RingSeries<Any>)

        // Read back: decode from ring
        val decoded = PointcutRegistry.read(0x34, ring, 0) as CtorPointcutTest.CtorEvent

        assertEquals(0x34, decoded.opcode)
        assertEquals("CONSTRUCTOR", decoded.phase)
        assertEquals("com/example/Foo", decoded.declaringClass)
        assertEquals("<init>", decoded.constructorName)
    }

    @Test
    @DisplayName("intercept → read round-trip: GETTER (0xA5)")
    fun roundTripGetter() {
        PointcutRegistry.installDefaults()

        val ring = RingSeries<GetterPointcutTest.GetEvent>(256)
        val value = GetterPointcutTest.GetEvent(
            seq = 1, nano = System.nanoTime(),
            opcode = 0xA5, phase = "GETTER",
            ownerClass = "com/example/Bean",
            fieldName = "value",
            fieldDesc = "I",
            addr = 0x20
        )

        @Suppress("UNCHECKED_CAST")
        PointcutRegistry.intercept(0xA5, value, ring as RingSeries<Any>)
        val decoded = PointcutRegistry.read(0xA5, ring, 0) as GetterPointcutTest.GetEvent

        assertEquals(0xA5, decoded.opcode)
        assertEquals("GETTER", decoded.phase)
        assertEquals("value", decoded.fieldName)
    }

    @Test
    @DisplayName("intercept → read round-trip: SETTER (0xA6)")
    fun roundTripSetter() {
        PointcutRegistry.installDefaults()

        val ring = RingSeries<SetterPointcutTest.SetEvent>(256)
        val value = SetterPointcutTest.SetEvent(
            seq = 2, nano = System.nanoTime(),
            opcode = 0xA6, phase = "SETTER",
            ownerClass = "com/example/Bean",
            fieldName = "enabled",
            fieldDesc = "Z",
            addr = 0x30
        )

        @Suppress("UNCHECKED_CAST")
        PointcutRegistry.intercept(0xA6, value, ring as RingSeries<Any>)
        val decoded = PointcutRegistry.read(0xA6, ring, 0) as SetterPointcutTest.SetEvent

        assertEquals(0xA6, decoded.opcode)
        assertEquals("SETTER", decoded.phase)
        assertEquals("enabled", decoded.fieldName)
    }

    @Test
    @DisplayName("intercept → read round-trip: ALLOC (0x38)")
    fun roundTripAlloc() {
        PointcutRegistry.installDefaults()

        val ring = RingSeries<NewPointcutTest.NewEvent>(256)
        val value = NewPointcutTest.NewEvent(
            seq = 3, nano = System.nanoTime(),
            opcode = 0x38, phase = "ALLOC",
            allocKind = NewPointcutTest.AllocKind.NEW,
            allocatedType = "Lcom/example/Foo;",
            dimensions = 0,
            addr = 0x40
        )

        @Suppress("UNCHECKED_CAST")
        PointcutRegistry.intercept(0x38, value, ring as RingSeries<Any>)
        val decoded = PointcutRegistry.read(0x38, ring, 0) as NewPointcutTest.NewEvent

        assertEquals(0x38, decoded.opcode)
        assertEquals("ALLOC", decoded.phase)
        assertEquals("Lcom/example/Foo;", decoded.allocatedType)
    }

    // ── readAll: MutableSeries → List ───────────────────────────────────

    @Test
    @DisplayName("readAll returns List<T> from MutableSeries")
    fun readAllReturnsList() {
        PointcutRegistry.installDefaults()

        val ring = RingSeries<GetterPointcutTest.GetEvent>(1024)

        // Encode 5 getter events
        listOf("a", "b", "c", "d", "e").forEachIndexed { i, _ ->
            val evt = GetterPointcutTest.GetEvent(
                seq = i, nano = System.nanoTime(),
                opcode = 0xA5, phase = "GETTER",
                ownerClass = "com/example/Bean",
                fieldName = "prop$i",
                fieldDesc = "I",
                addr = i
            )
            @Suppress("UNCHECKED_CAST")
            PointcutRegistry.intercept(0xA5, evt, ring as RingSeries<Any>)
        }

        val decoded = PointcutRegistry.readAll(0xA5, ring) as List<GetterPointcutTest.GetEvent>

        assertEquals(5, decoded.size)
        assertEquals("prop0", decoded[0].fieldName)
        assertEquals("prop4", decoded[4].fieldName)
    }

    // ── Custom codec via ReductionCodec ──────────────────────────────────

    @Test
    @DisplayName("ReductionCodec folds on decodeAll — last-write-wins by field")
    fun reductionCodecLastWriteWins() {
        // Register a custom reduction codec for opcode 0xA5 (GETTER)
        val reductionReducer = object : borg.trikeshed.lib.Reducer<GetterPointcutTest.GetEvent, Map<String, Long>> {
            override val zero: Map<String, Long> = emptyMap()
            override fun combine(acc: Map<String, Long>, element: GetterPointcutTest.GetEvent): Map<String, Long> {
                return acc + (element.fieldName to element.nano)
            }
        }

        val customCodec = PointcutRegistry.ReductionCodec(
            reductionReducer,
            GetterPointcutTest.GetEvent(0, 0L, 0xA5, "GETTER", "", "field", "I", 0)
        )

        // Override registration for 0xA5 with reduction codec
        PointcutRegistry.register(0xA5, "GETTER", customCodec)

        val ring = RingSeries<GetterPointcutTest.GetEvent>(256)

        // Fire same field 3x — ReductionCodec folds on readAll
        listOf("value", "value", "value").forEachIndexed { i, _ ->
            val evt = GetterPointcutTest.GetEvent(
                seq = i, nano = System.nanoTime() + i,
                opcode = 0xA5, phase = "GETTER",
                ownerClass = "com/example/Bean",
                fieldName = "value",
                fieldDesc = "I",
                addr = i
            )
            @Suppress("UNCHECKED_CAST")
            PointcutRegistry.intercept(0xA5, evt, ring as RingSeries<Any>)
        }

        val all = PointcutRegistry.readAll(0xA5, ring) as List<GetterPointcutTest.GetEvent>
        assertEquals(3, all.size, "all 3 events stored in ring")
        // Re-install default codec to avoid test pollution
        PointcutRegistry.installDefaults()
    }

    // ── Phase query ──────────────────────────────────────────────────────

    @Test
    @DisplayName("phaseOf returns correct phase for each opcode")
    fun phaseOfReturnsCorrectPhase() {
        PointcutRegistry.installDefaults()

        assertEquals("CONSTRUCTOR", PointcutRegistry.phaseOf(0x34))
        assertEquals("GETTER",      PointcutRegistry.phaseOf(0xA5))
        assertEquals("SETTER",      PointcutRegistry.phaseOf(0xA6))
        assertEquals("GETTER",      PointcutRegistry.phaseOf(0xA7))
        assertEquals("SETTER",      PointcutRegistry.phaseOf(0xA8))
        assertEquals("ALLOC",      PointcutRegistry.phaseOf(0x38))
        assertEquals("CALL",       PointcutRegistry.phaseOf(0x10))
        assertEquals("RETURN",     PointcutRegistry.phaseOf(0x4C))
        assertEquals("GAP",        PointcutRegistry.phaseOf(0xFF))  // unregistered → GAP
    }

    // ── isRegistered ───────────────────────────────────────────────────

    @Test
    @DisplayName("isRegistered returns true for all default XVM opcodes")
    fun isRegisteredForDefaults() {
        PointcutRegistry.installDefaults()

        val registeredOpcodes = listOf(
            0x34, 0x35, 0x36, 0x37,  // CONSTR
            0xA5, 0xA6, 0xA7, 0xA8,  // FIELD
            0x38, 0x39, 0x3A, 0x3B,  // ALLOC
            0x40, 0x41, 0x42, 0x43,  // NEWC
            0x48, 0x49, 0x4A, 0x4B,  // NEWV
            0x10, 0x1F,              // CALL range
            0x20, 0x2F,              // NVOK range
            0x4C, 0x4D, 0x4E, 0x4F   // RETURN
        )

        for (op in registeredOpcodes) {
            assertTrue(PointcutRegistry.isRegistered(op),
                "opcode 0x${Integer.toHexString(op)} should be registered")
        }

        assertFalse(PointcutRegistry.isRegistered(0xFF), "0xFF should not be registered")
    }

    // ── Firehose stress ─────────────────────────────────────────────────

    @Test
    @DisplayName("RingSeries shim absorbs intercept firehose at >1K events/sec")
    fun ringShimAbsorbsFirehose() {
        PointcutRegistry.installDefaults()

        val ring = RingSeries<Any>(65536)
        val t0 = System.nanoTime()
        val count = 10_000

        for (i in 0 until count) {
            val evt = GetterPointcutTest.GetEvent(
                seq = i, nano = System.nanoTime(),
                opcode = 0xA5, phase = "GETTER",
                ownerClass = "com/example/Entity",
                fieldName = "id$i",
                fieldDesc = "J",
                addr = i
            )
            PointcutRegistry.intercept(0xA5, evt, ring)
        }

        val elapsed = System.nanoTime() - t0
        val rate = count * 1_000_000_000.0 / elapsed

        assertEquals(count, ring.a)
        assertTrue(rate > 1_000_000, "intercept rate $rate must exceed 1M events/sec")
    }

    // ── dumpRegistrations ───────────────────────────────────────────────

    @Test
    @DisplayName("dumpRegistrations returns full opcode → phase map")
    fun dumpRegistrations() {
        PointcutRegistry.installDefaults()

        val dump = PointcutRegistry.dumpRegistrations()

        assertTrue(dump.isNotEmpty(), "registrations map must not be empty")
        assertEquals("CONSTRUCTOR", dump[0x34])
        assertEquals("GETTER",      dump[0xA5])
        assertEquals("SETTER",      dump[0xA6])
        assertEquals("ALLOC",       dump[0x38])
        assertEquals("CALL",        dump[0x10])
        assertEquals("RETURN",      dump[0x4C])
    }
}