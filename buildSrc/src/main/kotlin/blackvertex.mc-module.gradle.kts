import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * A Minecraft-facing module: everything the pure-JVM convention plugin does, plus a Java 25 toolchain
 * and the repositories Minecraft's dependencies come from. Its counterpart is `blackvertex.jvm-module`
 * — between them, which plugin a module applies says whether it may see the game.
 *
 * Loom itself is applied by the module, not here: `pluginManagement` in settings.gradle.kts does not
 * reach buildSrc's classpath, so a precompiled script plugin can only apply plugins that buildSrc
 * itself depends on — and dragging Loom in there would only duplicate its version.
 *
 * Which Minecraft version a module builds against is its own business (it says so in its
 * dependencies), so a second platform brings its own numbers without touching this file.
 */

plugins {
    kotlin("jvm")
    `maven-publish`
}

fun prop(name: String): String = providers.gradleProperty(name).get()

version = prop("mod_version")

// Same rule as `blackvertex.jvm-module` — fold the path into the group so same-named platform leaves
// stay distinct: :platform:26.2 -> org.blackaddons.platform:26.2
group = (listOf(prop("maven_group")) + project.path.removePrefix(":").split(":").dropLast(1))
    .joinToString(".")

val targetJavaVersion = 25

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://jitpack.io") { name = "JitPack" }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

// Published to JitPack as a self-contained fat jar: each platform's `jar` task bundles the :core
// classes, so the POM carries NO dependencies on purpose — the consumer is itself a Fabric mod that
// already provides Minecraft/Fabric/FLK, and a `project(":core")` line in the POM would only fail to
// resolve (:core is never published on its own). artifactId is the platform's archive name, e.g.
// `blackvertex-26.2`, so JitPack coordinates read com.github.dreamcastera1.blackvertex:blackvertex-26.2:<tag>.
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mod") {
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar"))
            afterEvaluate {
                artifactId = the<BasePluginExtension>().archivesName.get()
            }
        }
    }
}