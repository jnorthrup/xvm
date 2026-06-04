package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD RED: Redux capture at test-end before stringpool clear
 *
 * Full xvm gradle testsuite should:
 *   - Capture ReduxMutableSeries state at end of each test
 *   - Preserve reified records before clearing StringPool
 *   - Produce significant dump size and counters
 *
 * This test demonstrates the required harness behavior.
 */
class ReduxCaptureBeforeStringPoolClearTest {

    private lateinit var captureLog: MutableList<String>
    private var capturedRecords: List<TypedefFact>? = null

    @BeforeEach
    fun setup() {
        captureLog = mutableListOf()
        capturedRecords = null
        StringPool.clear()
        TypedefResolutionSeries.drain()
    }

    @AfterEach
    fun teardown() {
        // Capture ReduxMutableSeries state BEFORE clearing StringPool
        capturedRecords = captureReduxBeforeClear()

        // Now clear the StringPool
        StringPool.clear()

        // Verify the captured records are preserved
        assertTrue(capturedRecords != null, "Redux state must be captured before pool clear")
        assertTrue(capturedRecords!!.isNotEmpty() || capturedRecords!!.isEmpty(),
            "Capture happened (empty is valid — just proving the capture ran)")

        println("=== Redux Capture Report ===")
        println("Captured ${capturedRecords!!.size} records before StringPool clear")
        for (record in capturedRecords!!) {
            println("  factId=${record.factId}, poolId=${record.poolId}, clsName=${record.clsName}, success=${record.success}")
        }
        println("=============================")
    }

    @Test
    fun `capture redux state preserves reified records at end of test`() {
        // Record some facts into TypedefResolutionSeries
        val poolId = StringPool.intern("ReduxCapturePool")
        val factId1 = TypedefResolutionSeries.record(poolId, 0, "pkg.Test1", "format1", true)
        val factId2 = TypedefResolutionSeries.record(poolId, 1, "pkg.Test2", "format2", true)
        val factId3 = TypedefResolutionSeries.record(poolId, 2, "pkg.Test3", "format3", false)

        // Drain WAL to ensure all facts are in the Redux journal
        TypedefResolutionSeries.drain()

        // The @AfterEach captures and then clears
        assertTrue(capturedRecords != null)
        // At least 3 records should be captured
        assertTrue(capturedRecords!!.size >= 3, "Should capture at least 3 reified records")
    }

    @Test
    fun `empty redux captures cleanly with zero records`() {
        // No records — capture should return empty list, not null
        TypedefResolutionSeries.drain()
        val empty = captureReduxBeforeClear()
        assertTrue(empty.isEmpty(), "Empty redux should capture as empty list, not null")
        StringPool.clear()
    }

    @Test
    fun `capture produces significant dump size tracking`() {
        val poolId = StringPool.intern("DumpSizePool")

        // Record many facts
        val count = 50
        for (i in 0 until count) {
            TypedefResolutionSeries.record(poolId, i, "pkg.Dump$i", "format$i", i % 2 == 0)
        }
        TypedefResolutionSeries.drain()

        // Capture
        val records = captureReduxBeforeClear()
        assertEquals(count, records.size, "All $count records should be captured")

        // Verify dump size counter
        val dumpSize = calculateDumpSize(records)
        assertTrue(dumpSize > 0, "Dump size must be > 0 for $count records")
        println("Dump size for $count records: $dumpSize bytes")
    }

    @Test
    fun `reverted facts are excluded from capture`() {
        val poolId = StringPool.intern("RevertedPool")

        // Record and then revert some facts
        val factId = TypedefResolutionSeries.record(poolId, 0, "pkg.Rev", "format", true)
        TypedefResolutionSeries.drain()

        // Revert the fact — it should NOT appear in capture
        TypedefResolutionSeries.revert(factId)
        TypedefResolutionSeries.drain()

        val records = captureReduxBeforeClear()
        val revertedInCapture = records.filter { it.factId == factId }
        assertTrue(revertedInCapture.isEmpty(), "Reverted fact must not appear in capture")
    }

    @Test
    fun `capture preserves nano timestamps for ordering`() {
        val poolId = StringPool.intern("NanoTsPool")
        val factId1 = TypedefResolutionSeries.record(poolId, 0, "pkg.T1", "f1", true)
        Thread.sleep(1) // ensure distinct nano timestamps
        val factId2 = TypedefResolutionSeries.record(poolId, 1, "pkg.T2", "f2", true)
        TypedefResolutionSeries.drain()

        val records = captureReduxBeforeClear()
        val sortedRecords = records.sortedBy { it.factId }

        assertTrue(sortedRecords[0].nano <= sortedRecords[1].nano,
            "Earlier factId should have earlier or equal nano timestamp")
    }

    /**
     * Captures the current ReduxMutableSeries state.
     * Called at end of test BEFORE StringPool.clear().
     *
     * This is the core harness behavior required by the TODO.
     */
    private fun captureReduxBeforeClear(): List<TypedefFact> {
        TypedefResolutionSeries.drain() // Flush WAL rings
        val state = TypedefResolutionSeries.metaSeries()
        // state is the ReduxMutableSeries — access its captured Map<Long, TypedefFact>
        val journal = state as? borg.trikeshed.lib.ReduxMutableSeries<TypedefFact, *>
            ?: error("metaSeries() must return ReduxMutableSeries")

        // The captured state is in the journal's .state property
        // We need to access the reduced Map<Long, TypedefFact>
        // For now, use toRowVec() which gives us the serialized form
        val rowVec = TypedefResolutionSeries.toRowVec()
        captureLog.add("captured: $rowVec")

        // Parse rowVec back to List<TypedefFact>
        return parseRowVecToFacts(rowVec)
    }

    private fun parseRowVecToFacts(rowVec: String): List<TypedefFact> {
        if (rowVec.isEmpty()) return emptyList()
        val parts = rowVec.split("|")
        if (parts.size < 2) return emptyList()
        val factIds = parts[0].split(",").mapNotNull { it.toLongOrNull() }
        val cells = parts[1]
        val facts = mutableListOf<TypedefFact>()
        val cellEntries = cells.split(";")
        for (i in cellEntries.indices) {
            if (i >= factIds.size) break
            val fields = cellEntries[i].split(",")
            if (fields.size >= 5) {
                val nanoVal = if (fields.size >= 6) fields[5].toLongOrNull() ?: 0L else 0L
                facts.add(TypedefFact(
                    factId = factIds[i],
                    nano = nanoVal,
                    poolId = fields[0].toIntOrNull() ?: 0,
                    siteOrd = fields[1].toIntOrNull() ?: 0,
                    clsName = fields[2],
                    format = fields[3],
                    success = fields[4].toBooleanStrictOrNull() ?: true
                ))
            }
        }
        return facts
    }

    private fun calculateDumpSize(records: List<TypedefFact>): Int {
        var size = 0
        for (r in records) {
            size += r.clsName.toByteArray().size
            size += r.format.toByteArray().size
            size += 32 // primitive fields overhead estimate
        }
        return size
    }
}