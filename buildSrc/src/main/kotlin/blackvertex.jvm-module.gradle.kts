import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * A version-neutral, pure-JVM module: no fabric-loom, no Minecraft on the classpath — so a stray
 * `import net.minecraft.*` is a compile error rather than a convention. That enforced boundary is
 * the point: version-specific code physically cannot land in one of these. The parser, model,
 * skinner, animation sampling and attachment math live here and stay unit-testable off the game.
 */

plugins {
    kotlin("jvm")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

version = prop("mod_version")

// A project's identity to Gradle is `group:name:version`, and its name is the last segment of its
// path. Folding the path into the group keeps same-named leaves (e.g. two `26.2`s) apart:
//   :core             -> org.blackaddons:core
//   :platform:26.2    -> org.blackaddons.platform:26.2
group = (listOf(prop("maven_group")) + project.path.removePrefix(":").split(":").dropLast(1))
    .joinToString(".")

// Minecraft 26.2 requires Java 25 and is the only toolchain, so a lower target would buy nothing.
val targetJavaVersion = 25

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:${prop("junit_version")}"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// Libraries Minecraft provides at runtime are `compileOnly`, which leaves them off the test classpath
// as well — and a test of anything touching gson or JOML would then fail to compile. Tests run
// outside Minecraft, so they have to bring those themselves.
configurations.named("testImplementation") {
    extendsFrom(configurations.named("compileOnly").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
