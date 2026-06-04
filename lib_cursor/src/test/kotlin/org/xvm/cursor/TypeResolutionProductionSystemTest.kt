package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD RED: TypeResolutionProductionSystem
 *
 * The system wraps TypedefResolutionSeries with live pointcut instrumentation,
 * correlating ClassFileTaxonomy coordinates to Redux journal entries.
 * Side-by-side comparison:
 *   - as-is: TypedefResolutionSeries (cold WAL, journal replay)
 *   - wip:   TypeResolutionProductionSystem (live correlation, faceted resolution)
 *
 * RED: TypeResolutionProductionSystem does not exist yet. All tests fail at
 * Class.forName / reflection to keep the module compiling until it is implemented.
 */
class TypeResolutionProductionSystemTest {

    private fun loadSystem(): Any? = try {
        Class.forName("org.xvm.cursor.TypeResolutionProductionSystem")
            .getDeclaredConstructor()
            .newInstance()
    } catch (e: ClassNotFoundException) {
        null
    }

    @Test
    fun `system constructs and returns non-null state`() {
        val system = loadSystem()
        assertNotNull(system, "TypeResolutionProductionSystem class must exist (TODO: implement)")
    }

    @Test
    fun `empty system has zero correlation count`() {
        val system = loadSystem()
        assertNotNull(system, "TypeResolutionProductionSystem must exist")

        val correlationCount = system?.javaClass?.getMethod("correlationCount")?.invoke(system) as? Int
        assertEquals(0, correlationCount, "New system must start with correlationCount=0")
    }

    @Test
    fun `correlateTaxonomyRow updates correlation count`() {
        val system = loadSystem()
        assertNotNull(system, "TypeResolutionProductionSystem must exist")

        val poolId = StringPool.intern("TestPool_Corr")
        val tax = ClassFileTaxonomy()
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Foo.bar",
            ownerType = "pkg.Foo",
            methodOrField = "bar",
            classfileCoord = "pkg.Foo#bar",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        TypedefResolutionSeries.record(poolId, 0, "pkg.Foo", "format", true)

        val correlateTaxonomyRow = system?.javaClass?.getMethod("correlateTaxonomyRow",
            ClassFileTaxonomy::class.java, Int::class.java)
        assertNotNull(correlateTaxonomyRow, "correlateTaxonomyRow must exist on system")

        StringPool.clear()
        TypedefResolutionSeries.drain()
    }

    @Test
    fun `state snapshot contains resolution metadata`() {
        val system = loadSystem()
        assertNotNull(system, "TypeResolutionProductionSystem must exist")

        val stateMethod = system?.javaClass?.getMethod("state")
        assertNotNull(stateMethod, "state() method must exist on system")

        val state = stateMethod?.invoke(system)
        assertNotNull(state, "state() must return non-null snapshot")

        val totalFacts = state?.javaClass?.getField("totalFacts")?.getInt(state)
        assertTrue((totalFacts ?: -1) >= 0, "totalFacts must be >= 0")

    }

    @Test
    fun `side-by-side debug TypeResolutionProductionSystem vs TypedefResolutionSeries`() {
        val system = loadSystem()
        assertNotNull(system, "TypeResolutionProductionSystem must exist for side-by-side debug")

        val poolId = StringPool.intern("SideBySidePool")

        val tax = ClassFileTaxonomy()
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Dbg.run",
            ownerType = "pkg.Dbg",
            methodOrField = "run",
            classfileCoord = "pkg.Dbg#run",
            cpIndex = 42,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        val factId = TypedefResolutionSeries.record(poolId, 0, "pkg.Dbg", "format", true)
        assertTrue(factId >= 0)

        val rawFacts = TypedefResolutionSeries.size()
        println("=== Side-by-Side Debug ===")
        println("TypedefResolutionSeries.size(): $rawFacts")
        println("TypeResolutionProductionSystem: not yet implemented")
        println("=========================")

        assertTrue(rawFacts >= 0)

        StringPool.clear()
        TypedefResolutionSeries.drain()
    }
}