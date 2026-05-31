package borg.trikeshed.cursor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

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
        assertTrue(output.contains("FieldSynapse Slabs"))
    }

    @Test
    fun `redux timeseries cascade with xvm launch`() {
        assumeTrue(xdkLib.isDirectory && fizzBuzzXtc.isFile, "xvm artifacts not built")
        val output = runMode("xvm")
        assertTrue(output.contains("Redux timeseries + XVM launch"))
        assertTrue(output.contains("XVM FizzBuzz execution"))
        assertTrue(output.contains("cascade.csv"))
    }

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
                    .filterNot { it.contains("/javatools/build/libs/javatools-") || it.contains("/xdk/build/install/xdk/javatools/javatools.jar") }
            )
        }.joinToString(File.pathSeparator)

        val process = ProcessBuilder(
            "java",
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
