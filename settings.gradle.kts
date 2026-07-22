pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin_version").get()
        id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    }
}

rootProject.name = "blackvertex"

// One build, one project tree. The path says what a module is; the leaf says what it may see —
// `core` means no Minecraft on the classpath, a version number means Loom.
include("core")
include("platform:26.2")
include("platform:26.1.2")
