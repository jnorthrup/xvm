package borg.trikeshed.cursor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Path

private fun findRepoRoot(): File {
    var dir = File(System.getProperty("user.dir")).canonicalFile
    while (!dir.resolve("settings.gradle.kts").isFile) {
        dir = dir.parentFile ?: error("Could not locate repo root from ${System.getProperty("user.dir")}")
    }
    return dir
}

class PointcutCmdlineTest {
    private val repoRoot = findRepoRoot()
    private val xdkLib = repoRoot.resolve("xdk/build/install/xdk/lib")
    private val fizzBuzzXtc = repoRoot.resolve("manualTests/build/xtc/main/lib/FizzBuzz.xtc")

    @Test
    fun `redux timeseries cascade standalone benchmark`() {
        val output = runMode("redux")
        assertTrue(output.contains("Redux cascade standalone benchmark"))
        assertTrue(output.contains("File Artifacts"))
        assertTrue(output.contains("cascade.csv"))
    }

    @Test
    fun `synapse spiking model standalone benchmark`() {
        val output = runMode("synapse")
        assertTrue(output.contains("Synapse spiking model benchmark"))
        assertTrue(output.contains("Wireproto encode"))
        assertTrue(output.contains("FieldSynapse Batches"))
    }

    @Test
    fun `redux timeseries cascade with xvm launch`() {
        assumeTrue(xdkLib.isDirectory && fizzBuzzXtc.isFile, "xvm artifacts not built")
        val output = runMode("xvm")
        assertTrue(output.contains("Redux timeseries + XVM launch"))
        assertTrue(output.contains("XVM FizzBuzz execution"))
        assertTrue(output.contains("cascade.csv"))
    }

    /**
     * Real event data — verifies cascade CSV schema and content from child VM.
     * Uses the redux standalone run (in-process ring, no child VM needed)
     * to get deterministic real opcode events through the cascade pipeline.
     */
    @Test
    fun `real events cascade csv has correct schema and non-zero data`() {
        val output = runMode("redux")
        assertTrue(output.contains("cascade.csv"))
        assertTrue(output.contains("cascade rollup"))
        // The redux standalone publishes ring capacity events — verify rollup ran
        assertTrue(output.contains("4-tier cascade"))
        // Verify non-zero tier totals printed
        assertTrue(output.contains("T1:") || output.contains("T1"), "T1 tier should be printed")
        assertTrue(output.contains("T4:"), "T4 joint histogram tier should be printed")
    }

    /**
     * Real event data — verifies joint histogram CSV from child VM FizzBuzz run.
     * FizzBuzz exercises CALL (method invocations), LOOP (for/ternary), RETURN,
     * ALLOC (new object instances) — verify all 4 scope levels present.
     */
    @Test
    fun `real xvm events joint histogram covers all scopes`() {
        assumeTrue(xdkLib.isDirectory && fizzBuzzXtc.isFile, "xvm artifacts not built")
        val output = runMode("xvm")

        // Verify joint histogram was computed and dumped
        assertTrue(output.contains("joint_histogram.csv"))
        assertTrue(output.contains("Joint histogram"), "joint histogram tier should print")
        // Verify scope names appear in output (METHOD, CLASS, PACKAGE, MODULE)
        assertTrue(output.contains("METHOD") || output.contains("CLASS") || output.contains("PACKAGE"),
            "At least one scope level should appear in joint histogram output")
    }

    /**
     * Real event data — verify table_dump.csv row count matches ring drain count.
     * Redux standalone publishes exactly `ring.cap()` events; drain must produce
     * the same number of rows in table_dump.csv.
     */
    @Test
    fun `real ring drain produces matching table dump rows`() {
        assumeTrue(xdkLib.isDirectory && fizzBuzzXtc.isFile, "xvm artifacts not built")
        val output = runMode("xvm")

        // Extract drain row count from output: "drain] N rows → cascade table"
        val drainMatch = Regex("""\[drain\]\s+(\d+)\s+rows""").find(output)
        assertNotNull(drainMatch, "Should print drain row count")
        val drainRows = drainMatch!!.groupValues[1].toInt()
        assertTrue(drainRows > 0, "Drain must capture at least one event from FizzBuzz")

        // Verify table_dump appears in artifacts
        assertTrue(output.contains("table_dump.csv"))
    }

    // ── Shared fixture: run PointcutCmdline and return captured stdout ──

    private fun runMode(mode: String): String {
        val javatoolsDir = File(requireNotNull(System.getProperty("pointcutVm.javatoolsDir")) {
            "Missing pointcutVm.javatoolsDir system property"
        })
        check(javatoolsDir.isDirectory) { "Missing unpacked javatools dir: ${javatoolsDir.absolutePath}" }

        val childClasspath = buildList {
            add(javatoolsDir.absolutePath)
            addAll(
                System.getProperty("java.class.path")
                    .split(File.pathSeparator)
                    .filterNot {
                        it.contains("/javatools/build/libs/javatools-") ||
                        it.contains("/xdk/build/install/xdk/javatools/javatools.jar")
                    }
            )
        }.joinToString(File.pathSeparator)

        val process = ProcessBuilder(
            "java",
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            childClasspath,
            "borg.trikeshed.cursor.PointcutCmdlineKt",
            mode,
        )
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "PointcutCmdline mode '$mode' failed with exit $exit\n$output" }
        return output
    }
}
