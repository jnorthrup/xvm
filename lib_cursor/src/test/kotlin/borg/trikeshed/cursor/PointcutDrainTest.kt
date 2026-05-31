package borg.trikeshed.cursor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.xvm.runtime.*
import java.nio.file.Path

class PointcutDrainTest {

    // ── drain() happy path — 2 tests ────────────────────────────────────

    @Test
    fun `drain transitions RUNNING to DRAINING`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val table = tableWithEvents(5)
        val drain = PointcutDrain(lc, table, dir)
        drain.drain()
        assertTrue(lc.isDraining)
        assertFalse(lc.isShutdown)
    }

    @Test
    fun `drain writes cascade csv`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val table = tableWithEvents(5)
        val drain = PointcutDrain(lc, table, dir)
        drain.drain()
        val lines = dir.resolve("cascade.csv").toFile().readLines()
        assertTrue(lines.size >= 2, "cascade.csv must have header + 4 tier rows, got ${lines.size}")
    }

    // ── drain() guard: wrong state — 2 tests ───────────────────────────

    @Test
    fun `drain throws from INIT`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        val table = TypedefCascadeTable(256)
        assertThrows<IllegalStateException> {
            PointcutDrain(lc, table, dir).drain()
        }
    }

    @Test
    fun `drain throws from SHUTDOWN`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertThrows<IllegalStateException> {
            PointcutDrain(lc, TypedefCascadeTable(256), dir).drain()
        }
    }

    // ── drain() idempotent — 2 tests ───────────────────────────────────

    @Test
    fun `drain second call no-ops`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(3), dir)
        drain.drain()
        drain.drain()
        assertTrue(lc.isDraining)
        assertTrue(drain.isDrained)
    }

    @Test
    fun `drain second call does not rewrite files`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(3), dir)
        drain.drain()
        val size1 = dir.resolve("cascade.csv").toFile().length()
        drain.drain()
        val size2 = dir.resolve("cascade.csv").toFile().length()
        assertEquals(size1, size2)
    }

    // ── shutdown() happy — 2 tests ─────────────────────────────────────

    @Test
    fun `shutdown transitions DRAINING to SHUTDOWN`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(1), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(lc.isShutdown)
    }

    @Test
    fun `shutdown completes pipeline`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(1), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(drain.isShutDown)
        assertTrue(lc.isShutdown)
        assertFalse(lc.isRunning)
        assertFalse(lc.isDraining)
    }

    // ── shutdown() guard — 2 tests ─────────────────────────────────────

    @Test
    fun `shutdown throws from RUNNING`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        assertThrows<IllegalStateException> {
            PointcutDrain(lc, TypedefCascadeTable(256), dir).shutdown()
        }
    }

    @Test
    fun `shutdown throws from INIT`(@TempDir dir: Path) {
        assertThrows<IllegalStateException> {
            PointcutDrain(XvmLifecycle(), TypedefCascadeTable(256), dir).shutdown()
        }
    }

    // ── full pipeline — 2 tests ────────────────────────────────────────

    @Test
    fun `full pipeline produces all artifacts`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(dir.resolve("cascade.csv").toFile().exists())
        assertTrue(dir.resolve("joint_histogram.csv").toFile().exists())
        assertTrue(dir.resolve("table_dump.csv").toFile().exists())
        assertTrue(lc.isShutdown)
    }

    @Test
    fun `full pipeline csv has data rows`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        drain.shutdown()
        val dump = dir.resolve("table_dump.csv").toFile().readLines()
        assertEquals(11, dump.size, "header + 10 data rows")
        assertEquals("row,site_ord,kind,scope,success,depth,pool_id", dump[0])
    }

    // ── artifact content — 2 tests ─────────────────────────────────────

    @Test
    fun `cascade csv has 4 tier rows`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        val lines = dir.resolve("cascade.csv").toFile().readLines()
        assertEquals(5, lines.size, "header + 4 tier rows")
        assertTrue(lines[1].startsWith("1,"), "first tier is 1")
        assertTrue(lines[4].startsWith("4,"), "last tier is 4")
    }

    @Test
    fun `joint histogram csv has 36 rows`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        val lines = dir.resolve("joint_histogram.csv").toFile().readLines()
        // 9 kinds x 4 scopes = 36 data rows + 1 header = 37
        assertEquals(37, lines.size, "header + 9x4 joint histogram rows")
    }

    // ── helper ──────────────────────────────────────────────────────────

    private fun tableWithEvents(n: Int): TypedefCascadeTable {
        val table = TypedefCascadeTable(256)
        repeat(n) { i ->
            table.routeOpcode(0x10 + (i % 4), "pkg.Class.method$i", 100 + i)
        }
        return table
    }
}
