// :platform:26.1.2 — the Minecraft 26.1.2 integration. Shares the version-neutral render code with
// 26.2 through platform/shared/; its own half is the GPU backend (26.1.2's blaze3d idiom + a mixin
// injection, since 26.1.2 has no open feature-renderer system) and the client entrypoint.
plugins {
    id("blackvertex.mc-module")
    id("net.fabricmc.fabric-loom")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

val minecraftVersion = prop("mc2611_minecraft_version")
val loaderVersion = prop("mc2611_loader_version")
val kotlinLoaderVersion = prop("mc2611_kotlin_loader_version")
val fabricVersion = prop("mc2611_fabric_version")

base {
    archivesName = prop("mc2611_archives_base_name")
}

// Same shared source dir 26.2 compiles — against 26.1.2's Minecraft here. See platform/shared/.
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir("../shared/src/main/kotlin")
    }
}

// Shared assets (demo models/textures + the placeholder used by BlackVertexTextures).
sourceSets.named("main") {
    resources.srcDir("../shared/src/main/resources")
}

// The demo is gated on this flag, not isDevelopment, so it never leaks into a shipped jar. Enable it
// for the dev client — it reaches the forked game JVM, which a `-D` on the gradle command line would not.
loom {
    runConfigs.named("client") {
        property("blackvertex.demo", "true")
    }
}

dependencies {
    implementation(project(":core"))

    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

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

// Bundle the version-neutral :core classes into the jar (Loom ships only this module's own).
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
    from(bundledCore.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**")
    }
    from(rootProject.file("LICENSE.txt")) {
        rename { "LICENSE_${base.archivesName.get()}" }
    }
}
