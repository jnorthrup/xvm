import XdkDistribution.Companion.DISTRIBUTION_TASK_GROUP
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP

/*
 * Main build file for the XVM project, producing the XDK.
 */

plugins {
    alias(libs.plugins.xdk.build.aggregator)
    alias(libs.plugins.xdk.build.properties)
}

// Root aggregator: version set automatically by properties plugin
group = xdkProperties.stringValue("xdk.group")
version = xdkProperties.stringValue("xdk.version")

logger.info("[xvm] Root aggregator version: $group:$name:$version")

/**
 * Print version information for the root aggregator and all included builds.
 * The aggregator plugin creates this task and adds dependencies to all included builds.
 * We configure it here to also print the root aggregator's version.
 */
val versions by tasks.existing {
    // Capture values during configuration for configuration cache compatibility
    val projectName = project.name
    val projectGroup = project.group
    val projectVersion = project.version

    doFirst {
        logger.lifecycle("\n📦 Root Aggregator: $projectName")
        logger.lifecycle("   $projectGroup:$projectName:$projectVersion")
        logger.lifecycle("")
    }
}

/**
 * Installation and distribution tasks that aggregate publishable/distributable included
 * build projects. The aggregator proper should be as small as possible, and only contains
 * LifeCycle dependencies, aggregated through the various included builds. This creates as
 * few bootstrapping problems as possible, since by the time we get to the configuration phase
 * of the root build.gradle.kts, we have installed convention plugins, resolved version catalogs
 * and similar things.
 */

val distZip by tasks.registering {
    group = DISTRIBUTION_TASK_GROUP
    description = "Build the XDK distribution zip in the xdk/build/distributions directory."
    dependsOn(xdk.task(":$name"))
}

val installDist by tasks.registering {
    group = DISTRIBUTION_TASK_GROUP
    description = "Install the XDK distribution in the xdk/build/distributions and xdk/build/install directories."
    dependsOn(xdk.task(":$name"))
}

val installWithNativeLaunchersDist by tasks.registering {
    group = DISTRIBUTION_TASK_GROUP
    description = "Install the XDK distribution with native launchers in the xdk/build/install directory."
    dependsOn(xdk.task(":$name"))
}

private val xdk = gradle.includedBuild("xdk")
private val plugin = gradle.includedBuild("plugin")
private val publishedBuilds = listOf(xdk, plugin)

val publishLocal by tasks.registering {
    group = PUBLISH_TASK_GROUP
    description = "Publish XDK and plugin artifacts to local Maven repository."

    // Publish to local Maven repository for all included builds with publications
    publishedBuilds.forEach { build ->
        dependsOn(build.task(":publishToMavenLocal"))
    }
}

val publishSnapshotBundle by tasks.registering {
    group = PUBLISH_TASK_GROUP
    description = "Publish XDK and plugin snapshot artifacts to an isolated file-backed Maven repository."

    val snapshotBundleRepoProvider = xdkProperties.string("org.xtclang.publish.snapshotBundleRepo")
    val versionProvider = xdkProperties.string("xdk.version")

    doFirst {
        val snapshotBundleRepo = snapshotBundleRepoProvider.orNull?.trim().orEmpty()
        if (snapshotBundleRepo.isEmpty()) {
            throw GradleException(
                "❌ Missing required property: -Porg.xtclang.publish.snapshotBundleRepo=/path/to/staged/maven/repo"
            )
        }
        val currentVersion = versionProvider.get()
        if (!currentVersion.endsWith("-SNAPSHOT")) {
            throw GradleException(
                "❌ publishSnapshotBundle only supports SNAPSHOT versions. Current version: $currentVersion"
            )
        }
        logger.lifecycle("📦 Publishing snapshot bundle to local Maven repository: $snapshotBundleRepo")
    }

    publishedBuilds.forEach { build ->
        dependsOn(build.task(":publishAllPublicationsToSnapshotBundleRepository"))
    }
}

/**
 * Publish XDK and plugin artifacts to both local Maven and remote repositories.
 *
 * Publishes to both local Maven and enabled remote repositories
 * (GitHub Packages, Maven Central, Gradle Plugin Portal).
 *
 * Options:
 * - Use -Porg.xtclang.allowRelease=true to allow publishing release versions (required for non-SNAPSHOT versions)
 */
val publish by tasks.registering {
    group = PUBLISH_TASK_GROUP
    description = "Publish XDK and plugin artifacts to both local Maven and remote repositories."

    // Capture version and allowRelease as Providers for configuration cache compatibility
    val versionProvider = xdkProperties.string("xdk.version")
    val allowReleaseProvider = xdkProperties.boolean("org.xtclang.allowRelease", false)

    doFirst {
        // Safety check: prevent accidental release publishing
        val currentVersion = versionProvider.get()
        val isSnapshot = currentVersion.endsWith("-SNAPSHOT")
        val allowRelease = allowReleaseProvider.getOrElse(false)

        if (!isSnapshot && !allowRelease) {
            throw GradleException(
                """
                |❌ Cannot publish release version without explicit approval!
                |
                |Current version: $currentVersion
                |
                |This is a RELEASE version (no -SNAPSHOT suffix).
                |To publish a release, you must explicitly set -Porg.xtclang.allowRelease=true
                |
                |Example: ./gradlew publish -Porg.xtclang.allowRelease=true
                |
                |This safety check prevents accidental release publishing.
                """.trimMargin()
            )
        }
        logger.lifecycle("${if (isSnapshot) "📦" else "⚠️ "} Publishing ${if (isSnapshot) "SNAPSHOT" else "RELEASE"} version: $currentVersion (allowRelease=$allowRelease)")
    }

    // Validate credentials before attempting remote publishing (use xdk's validateCredentials task)
    dependsOn(xdk.task(":validateCredentials"))

    // Always publish to both local and remote
    dependsOn(publishLocal)

    // Publish to all enabled remote repositories for all included builds with publications
    // The :publish task will publish to all repositories enabled via properties
    publishedBuilds.forEach { build ->
        dependsOn(build.task(":publish"))
    }
}

/**
 * Aggregate validateCredentials task that runs validation in all publishable projects.
 */
val validateCredentials by tasks.registering {
    group = PUBLISH_TASK_GROUP
    description = "Validate all publishing credentials across all projects without publishing"

    // Run validateCredentials in all projects with publications
    publishedBuilds.forEach { build ->
        dependsOn(build.task(":validateCredentials"))
    }
}

/**
 * Docker tasks - forwarded to docker subproject
 * TODO: Skip this and resolve the dist some other way.
 */

private val dockerSubproject = gradle.includedBuild("docker")
private val dockerTaskNames = listOf(
    "dockerBuildAmd64", "dockerBuildArm64", "dockerBuild",
    "dockerBuildMultiPlatform", "dockerPushMultiPlatform",
    "dockerPushAmd64", "dockerPushArm64", "dockerPushAll",
    "dockerBuildAndPush", "dockerBuildAndPushMultiPlatform",
    "dockerCreateManifest", "dockerBuildPushAndManifest"
)

// Forward all docker tasks to the docker subproject
dockerTaskNames.forEach { taskName ->
    tasks.register(taskName) {
        group = "docker"
        description = "Forward to docker subproject task: $taskName"
        dependsOn(dockerSubproject.task(":$taskName"))

        // Ensure XDK is built first for tasks that need it
        if (taskName.contains("Build") || taskName.contains("Push")) {
            dependsOn(installDist)
        }
    }
}

/**
 * Mirror the xdk runtime (.xtc modules) into the path the javatools TypedefTest reads.
 *
 * The test hardcodes `lib_ecstasy/build/xtc/main/lib` as the module path. The xdk
 * composite build's `prepareXdkRuntime` task stages the .xtc modules at
 * `xdk/build/xdk-runtime/lib/`, so we copy them into the test's expected location.
 *
 * This makes `:javatools:test` self-bootstrapping: no need to run `xdk:installDist`
 * (which is monolithic and slow) just to satisfy the test's module path.
 */
val xdkRuntime by tasks.registering(Copy::class) {
    group = "verification"
    description =
        "Copy xdk/build/xdk-runtime/lib/*.xtc into lib_ecstasy/build/xtc/main/lib for the javatools tests."

    from(layout.buildDirectory.dir("xdk/build/xdk-runtime/lib"))
    into(layout.buildDirectory.dir("lib_ecstasy/build/xtc/main/lib"))

    // Trigger the xdk runtime staging first. prepareXdkRuntime is incremental and only
    // re-stages when xtc sources change.
    dependsOn(xdk.task(":prepareXdkRuntime"))
}

// Make :javatools:test self-bootstrap its XDK runtime. This avoids requiring the full
// xdk:installDist (which rebuilds scripts, archives, and the entire distribution) just
// to satisfy the test's module path.
val javatoolsTest by tasks.registering {
    group = "verification"
    description =
        "Run :javatools:test with a freshly staged xdk runtime. Lightweight alternative to running the full xdk:installDist first."

    dependsOn(xdkRuntime)
    dependsOn(gradle.includedBuild("javatools").task(":test"))
}

val testMixin by tasks.registering {
    group = "verification"
    description = "Run TypedefTest.testMixin with a freshly staged xdk runtime."

    dependsOn(xdkRuntime)
    dependsOn(gradle.includedBuild("javatools").task(":testMixin"))
}

// Stage pre-built .xtc modules by bootstrapping: build with the pre-typedef compiler,
// then the test runs with our modified javatools layered on top.
val stageXtcLibs by tasks.registering {
    group = "verification"
    description = "Bootstrap xtc libs from pre-typedef compiler for TestMixins"
    outputs.upToDateWhen { false } // always re-stage

    doLast {
        val rootDir = project.projectDir

        // Step 1: Save our modified javatools source
        val backupDir = rootDir.resolve("javatools/.typedef_work")
        val srcDir = rootDir.resolve("javatools/src/main/java")
        backupDir.mkdirs()

        // Step 2: Check out the pre-typedef javatools source
        ant.withGroovyBuilder {
            "exec"("executable" to "git",
                "dir" to rootDir.absolutePath,
                "failonerror" to true) {
                "arg"("value" to "checkout")
                "arg"("value" to "2ed5774d4")
                "arg"("value" to "--")
                "arg"("value" to "javatools/src/main/java")
            }
        }

        // Step 3: Build javatools + all xtc libs with the old compiler
        val procBuild = ProcessBuilder("./gradlew",
                ":javatools:jar",
                ":xdk:lib-ecstasy:compileXtc",
                ":xdk:javatools-bridge:compileXtc",
                "--no-configuration-cache")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        procBuild.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        val exitBuild = procBuild.waitFor()
        if (exitBuild != 0) {
            throw GradleException("Bootstrap build failed with exit code $exitBuild")
        }

        // Step 4: Copy the pre-built bridge to the runner's discovery path
        val bridgeDir = rootDir.resolve("javatools_bridge/build/xtc/main/lib")
        bridgeDir.mkdirs()

        // Step 5: Restore our modified javatools source
        ant.withGroovyBuilder {
            "exec"("executable" to "git",
                "dir" to rootDir.absolutePath,
                "failonerror" to true) {
                "arg"("value" to "checkout")
                "arg"("value" to "HEAD")
                "arg"("value" to "--")
                "arg"("value" to "javatools/src/main/java")
            }
        }

        // Step 6: Rebuild javatools with our typedef changes
        // First delete the old jar so gradle doesn't use the cached version
        rootDir.resolve("javatools/build/libs/javatools-${project.version}.jar").delete()
        val procOur = ProcessBuilder("./gradlew",
                ":javatools:jar",
                "--no-configuration-cache")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        procOur.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        val exitOur = procOur.waitFor()
        if (exitOur != 0) {
            throw GradleException("Our javatools build failed with exit code $exitOur")
        }

        // Step 7: Build mack.xtc from source with our javatools
        val ecstasyDir = rootDir.resolve("lib_ecstasy/build/xtc/main/lib")
        val mackSrc = rootDir.resolve("javatools_turtle/src/main/resources/mack.x")
        val mackOut = rootDir.resolve("javatools/build/test-mack-staging")
        mackOut.mkdirs()
        val cp = "javatools/build/libs/javatools-${project.version}.jar" +
                ":javatools_utils/build/libs/javatools-utils-${project.version}.jar"
        val procMack = ProcessBuilder("java", "-cp", cp,
                "org.xvm.tool.Launcher", "build",
                "-L", ecstasyDir.absolutePath,
                "-o", mackOut.absolutePath,
                mackSrc.absolutePath)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        procMack.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        val exitMack = procMack.waitFor()
        if (exitMack != 0) {
            throw GradleException("mack.xtc build failed with exit code $exitMack")
        }
        mackOut.resolve("mack.xtc").copyTo(ecstasyDir.resolve("mack.xtc"), overwrite = true)

        logger.lifecycle("[stageXtcLibs] Bootstrap complete: ecstasy.xtc + mack.xtc + javatools_bridge.xtc + 17 lib_*.xtc")
    }
}

val TestMixins by tasks.registering {
    group = "verification"
    description = "Compile test_mixin.x (requires stageXtcLibs first, or run TestMixinsRun)"

    dependsOn(stageXtcLibs)
    dependsOn(gradle.includedBuild("javatools").task(":testMixin"))
}

val TestMixinsRun by tasks.registering(Exec::class) {
    group = "verification"
    description = "Full TDD: bootstrap xtc libs, compile and execute test_mixin.x with verbose output"

    dependsOn(stageXtcLibs)

    val runner = file("bin/xtc_runner.sh")
    executable = runner.absolutePath
    args = listOf("test_mixin")
}

