// :core — the version-independent library: mesh formats (.bobj / geckolib / cuboids), the model +
// skeleton types, CPU skinning, animation sampling and attachment math. No Minecraft on the
// classpath (the convention plugin enforces it), so all of this stays unit-testable off the game.
plugins {
    id("blackvertex.jvm-module")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

dependencies {
    // All provided by Minecraft at runtime, so compileOnly — the platform module never bundles them.
    // Gson + JOML: geckolib .json parsing (GeoJsonLoader). SLF4J: warn logs on skipped bones/clips.
    compileOnly("com.google.code.gson:gson:${prop("gson_version")}")
    compileOnly("org.joml:joml:${prop("joml_version")}")
    compileOnly("org.slf4j:slf4j-api:${prop("slf4j_version")}")
}
