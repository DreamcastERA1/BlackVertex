// Home of the convention plugins — the shared build logic every module applies instead of repeating
// it. Written as precompiled script plugins, so a module's own build file states only what is true of
// that module.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// buildSrc is its own Gradle build, so `providers.gradleProperty` reads *its* properties, not the
// root's — hence the root file is read directly. The version lives in exactly one place (the root
// gradle.properties), which settings.gradle.kts pins the plugin to as well.
val kotlinVersion: String = file("../gradle.properties").readLines()
    .firstOrNull { it.startsWith("kotlin_version=") }
    ?.substringAfter('=')
    ?.trim()
    ?: error("kotlin_version missing from the root gradle.properties")

dependencies {
    // The Kotlin plugin has to be on this classpath for a precompiled script plugin to apply
    // `kotlin("jvm")`.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
}
