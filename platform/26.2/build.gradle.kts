// :platform:26.2 — the Minecraft 26.2 integration: the feature layer, the CPU and GPU render paths,
// texture registration and the client entrypoint. A sibling platform (26.1.2) will declare its own
// numbers and its own GPU backend; the version-neutral half lives in :core.
plugins {
    id("blackvertex.mc-module")
    // Version pinned in settings.gradle.kts. Applied here rather than in the convention plugin — see
    // that plugin's comment.
    id("net.fabricmc.fabric-loom")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

// Everything version-specific about 26.2, in one place.
val minecraftVersion = prop("mc262_minecraft_version")
val loaderVersion = prop("mc262_loader_version")
val kotlinLoaderVersion = prop("mc262_kotlin_loader_version")
val fabricVersion = prop("mc262_fabric_version")

base {
    archivesName = prop("mc262_archives_base_name")
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
// each platform's jar. The 26.2-only GPU shaders stay in this module's own resources.
sourceSets.named("main") {
    resources.srcDir("../shared/src/main/resources")
}

loom {
    runConfigs.named("client") {
        // The demo (tail + ears + /blackvertex-* commands) is gated on this flag, not isDevelopment,
        // so it never leaks into a shipped jar (see BlackvertexClient). Enable it for the dev client
        // — it reaches the forked game JVM, which a `-D` on the gradle command line would not.
        property("blackvertex.demo", "true")
    }
}

dependencies {
    implementation(project(":core"))

    // 26.2 ships deobfuscated → no `mappings(...)` line, and Fabric artifacts come in via plain
    // `implementation(...)` instead of `modImplementation(...)`.
    minecraft("com.mojang:minecraft:$minecraftVersion")
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
