import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * A Minecraft-facing module: everything the pure-JVM convention plugin does, plus a Java 25 toolchain
 * and the repositories Minecraft's dependencies come from. Its counterpart is `blackvertex.jvm-module`
 * — between them, which plugin a module applies says whether it may see the game.
 *
 * It ends by applying Loom (at `loom_version`, which buildSrc puts on its classpath) and building the
 * platform: the shared sources, Minecraft and Fabric, the bundled :core and the dev demo flag. The
 * folder name is the Minecraft version and picks the `mc<version>_*` properties, so a platform's own
 * build file only applies this.
 */

plugins {
    kotlin("jvm")
    `maven-publish`
}

fun prop(name: String): String = providers.gradleProperty(name).get()

version = prop("mod_version")

// Same rule as `blackvertex.jvm-module` — fold the path into the group so same-named platform leaves
// stay distinct: :platform:26.3 -> org.blackaddons.platform:26.3
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
// `blackvertex-26.3`, so JitPack coordinates read com.github.dreamcastera1.blackvertex:blackvertex-26.3:<tag>.
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

/* Applied after the repositories above, as it was when each platform applied it. */
apply(plugin = "net.fabricmc.fabric-loom")

val mc = project.name
fun mcProp(name: String): String = prop("mc${mc.replace(".", "")}_$name")

val minecraftVersion = mcProp("minecraft_version")
val loaderVersion = mcProp("loader_version")
val kotlinLoaderVersion = mcProp("kotlin_loader_version")
val fabricVersion = mcProp("fabric_version")

base {
    archivesName = mcProp("archives_base_name")
}

// Platform code every MC platform shares: the feature layer, the CPU render path, textures and the
// GpuBackend seam. These sources use only MC API names that hold across the versions we target, so
// each platform compiles the same files against its own Minecraft — which is why they are a source
// directory and not a module (see platform/shared/).
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir("../shared/src/main/kotlin")
    }
}

// Shared assets (demo models/textures + the placeholder used by BlackVertexTextures) compiled into
// each platform's jar. Each version's GPU shaders stay in its module's own resources.
sourceSets.named("main") {
    resources.srcDir("../shared/src/main/resources")
}

configure<LoomGradleExtensionAPI> {
    runConfigs.named("client") {
        // The demo (tail + ears + /blackvertex-* commands) is gated on this flag, not isDevelopment,
        // so it never leaks into a shipped jar (see BlackvertexClient). Enable it for the dev client
        // — it reaches the forked game JVM, which a `-D` on the gradle command line would not.
        systemProperties.put("blackvertex.demo", "true")
    }
}

dependencies {
    implementation(project(":core"))

    // Minecraft ships deobfuscated → no `mappings(...)` line, and Fabric artifacts come in via plain
    // `implementation(...)` instead of `modImplementation(...)`.
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion,
        )
    }
}

// Loom ships only this module's own classes, so the version-neutral :core classes have to be copied
// into the jar as well — without them the client dies on ClassNotFoundException. They arrive as a
// *jar*, resolved through a configuration, so nothing is asked of :core at configuration time.
val bundledCore: Configuration = configurations.create("bundledCore") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    bundledCore(project(":core"))
}

tasks.jar {
    // `elements` carries the producing task, so `from(...)` picks up the dependency without a
    // hand-written `dependsOn`.
    from(bundledCore.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**")
    }
    from(rootProject.file("LICENSE.txt")) {
        rename { "LICENSE_${base.archivesName.get()}" }
    }
}
