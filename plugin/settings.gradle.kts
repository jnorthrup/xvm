pluginManagement {
    includeBuild("../build-logic/settings-plugins")
    includeBuild("../build-logic/common-plugins")
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id("org.xtclang.build.common")
}

rootProject.name = "xtc-plugin"
