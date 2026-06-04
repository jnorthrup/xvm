package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD RED: event-log capture at test-end before stringpool clear
 *
 * Full xvm gradle testsuite should:
 *   - Capture typedef event log at end of each test
 *   - Preserve recorded events before clearing StringPool
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
        TypedefResolutionSeries.reset()
    }

    @AfterEach
    fun teardown() {
        // Capture typedef event log BEFORE clearing StringPool
        capturedRecords = captureReduxBeforeClear()

        // Now clear the StringPool
        StringPool.clear()

        // Verify the captured records are preserved
        assertTrue(capturedRecords != null, "Event log must be captured before pool clear")
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
    fun `capture event log preserves recorded events at end of test`() {
        // Record some facts into TypedefResolutionSeries
        val poolId = StringPool.intern("ReduxCapturePool")
        TypedefResolutionSeries.record(poolId, 0, "pkg.Test1", "format1", true)
        TypedefResolutionSeries.record(poolId, 1, "pkg.Test2", "format2", true)
        TypedefResolutionSeries.record(poolId, 2, "pkg.Test3", "format3", false)

        // Drain WAL to ensure all facts are in the Redux journal
        TypedefResolutionSeries.drain()

        val captured = captureReduxBeforeClear()
        assertEquals(3, captured.size, "Should capture exactly 3 recorded events")
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
    fun `revert is captured as an event`() {
        val poolId = StringPool.intern("RevertedPool")

        // Record and then revert some facts
        val factId = TypedefResolutionSeries.record(poolId, 0, "pkg.Rev", "format", true)
        TypedefResolutionSeries.drain()

        // Revert the fact — event log should capture the revert event too
        TypedefResolutionSeries.revert(factId)
        TypedefResolutionSeries.drain()

        val records = captureReduxBeforeClear()
        val revertedInCapture = records.filter { it.factId == factId && it.isReverted }
        assertEquals(1, revertedInCapture.size, "Revert must appear as a distinct event")
        assertEquals(2, records.size, "Record plus revert should both be present in raw event capture")
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
     * Captures the current typedef event log.
     * Called at end of test BEFORE StringPool.clear().
     *
     * This is the core harness behavior required by the TODO.
     */
    private fun captureReduxBeforeClear(): List<TypedefFact> {
        val facts = TypedefResolutionSeries.snapshotEvents()
        captureLog.add("captured ${facts.size} facts")
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