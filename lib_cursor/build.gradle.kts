plugins {
    kotlin("jvm") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.9"
    id("application")
    id("me.champeau.jmh") version "0.7.2"
    id("java-library")
}

group = "org.xvm"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

// TrikeShed from mavenLocal — used for benchmarks
val trikeShedJar = layout.projectDirectory.file("../../TrikeShed/build/libs/TrikeShed-jvm-1.0.jar")
// javatools on classpath so KSP resolver can see org.xvm.asm.* types
val javatoolsJar = layout.projectDirectory.file("../javatools/build/libs/javatools-0.4.4-SNAPSHOT.jar")
dependencies {
    implementation(files(trikeShedJar))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")

    // KSP — compile-time Series type dispatch (replaces ToSeriesMacro + codec hack)
    ksp("org.xvm:processor:0.1.0-SNAPSHOT")
    implementation("org.xvm:annotations:0.1.0-SNAPSHOT")

    // javatools — compileOnly so KSP can resolve asm types, not shipped in lib_cursor
    compileOnly(files(javatoolsJar))

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.junit.platform:junit-platform-engine:6.0.3")
    testImplementation("org.junit.platform:junit-platform-launcher:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests.set(false)
}

val runMacro by tasks.registering(JavaExec::class) {
    val mainSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("main")
    classpath = mainSourceSet.runtimeClasspath
    mainClass.set("borg.trikeshed.cursor.ToSeriesMacroKt")
    if (project.hasProperty("macroArgs")) {
        args = (project.property("macroArgs") as String).split(" ")
    }
}

application {
    mainClass.set("borg.trikeshed.cursor.BlackboardTimeseriesKt")
}

// ── JMH opt-out via -Pjmh=false ─────────────────────────────────────────────
val jmhDisabled = providers.gradleProperty("jmh").orNull == "false"
if (jmhDisabled) {
    tasks.matching { it.name.startsWith("jmh") }.configureEach { enabled = false }
    tasks.named("compileJmhKotlin") { enabled = false }
}