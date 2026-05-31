buildCache {
    local {
        directory = file("../../.gradle/build-cache")
    }
}

pluginManagement {
    includeBuild("../settings-plugins")
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id("org.xtclang.build.common")
}

rootProject.name = "common-plugins"
