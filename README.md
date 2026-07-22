# BlackVertex

[![JitPack](https://jitpack.io/v/DreamcastERA1/BlackVertex.svg)](https://jitpack.io/#DreamcastERA1/BlackVertex)

### I totally didn't make docs with AI. Totally.
###### And some comments and code too

A Fabric **client** library that renders skeletal, animated meshes on players as cosmetics
— tails, ears, wings, and the like. Kotlin, Minecraft 26.1.2 and 26.2.

Vanilla entity rendering only speaks cuboids. BlackVertex renders arbitrary triangle meshes
with bone skinning and keyframe animation, and skins them **on the GPU** — static vertex
buffers plus a bone-palette UBO per draw, with no per-frame vertex emission. It runs on both
render backends (OpenGL and Vulkan) through Mojang's own pipeline abstraction, and falls back
to CPU skinning automatically when the GPU path is unavailable (e.g. under a shader pack).

It was built to back the cosmetics system in BlackAddons, but the API is self-contained: give
it a model, a texture and an attachment point, and it draws.

## Supported versions

Each Minecraft version is a separate, self-contained artifact — the version-neutral core
(parsers, skinning math, animation) is compiled into both:

| Minecraft | Artifact             |
|-----------|----------------------|
| 26.2      | `blackvertex-26.2`   |
| 26.1.2    | `blackvertex-26.1.2` |

Both ship the GPU skinning path. They differ only in how the draw is injected into the frame
(26.2 uses Fabric's feature-renderer system; 26.1.2 a small mixin), which is invisible to you.

## Installation

Published via [JitPack](https://jitpack.io/#DreamcastERA1/BlackVertex). The library is itself a Fabric mod (it registers
its own render layer at init), so depend on it as a mod and nest it into your jar:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    // Pick the artifact matching your Minecraft version; <tag> is a release tag or commit hash.
    implementation("com.github.DreamcastERA1.BlackVertex:blackvertex-26.2:<tag>")
    include("com.github.DreamcastERA1.BlackVertex:blackvertex-26.2:<tag>")
}
```

The jar is self-contained — the version-neutral core classes are bundled in, so there is no
second dependency to add.

## Quick start

```kotlin
// 1. Parse a model (dispatched to the right loader by file extension).
val model = ModelFormats.load("tail.bobj", bobjText)

// 2. Register it as a cosmetic. BlackVertex draws it on every player from here on.
PlayerCosmetics.add(
    PlayerCosmetic(
        model = model,
        clip = "idle",                 // animation clip name, or null for the bind pose
        texture = myTextureId,         // e.g. baked via TextureCompositor + DynamicTextures
        attach = AttachmentPoint.LOWER_BACK.attachment,
    )
)
```

`PlayerCosmetics.init()` runs from the library's own client entrypoint — you only add and
remove cosmetics. Removal (`PlayerCosmetics.remove` / `clear`) frees the model's GPU buffers
once nothing references it.

## Concepts

**Models & formats.** `ModelFormats.load(fileName, text)` picks a `ModelLoader` by extension
and returns a renderer-agnostic `Model` (meshes + skeleton + animation clips). Built-in
formats are listed below; register your own with `ModelFormats.register(loader)` — no render
code changes.

**Cosmetics.** A `PlayerCosmetic` bundles a model with how it should look and move: `clip`,
`texture`, an optional `emissive` glow layer (its alpha is the glow mask), `translucent`
blending, a `color` (ARGB modulator over the whole cosmetic — an alpha below `0xFF` renders it
as a translucent "ghost", the RGB tints it; mutable for fades/flashes), a playback `speed`, and
an `attach`. Add and remove them at any time — the list can be streamed from a backend and the
render layer reads it every frame.

**Attachment.** `AttachmentPoint` presets (head, lower/upper back, wrists, ankles, waist)
place a cosmetic on a body part so it rides that part's animated motion. Tweak a preset with
`AttachmentPoint.X.effective(...)` — by default the fields are *corrections* added on top of
the preset, so zeros land exactly on it. `PlayerCosmetics.renderDistanceBlocks` (default 32)
skips cosmetics on distant players.

**Textures.** Cosmetics are textured externally. `TextureCompositor` optionally bakes a
layered scheme — a grayscale base tinted by a primary color, plus alpha-masked overlays tinted
by accent colors — into one image; per-overlay `supportsAlpha` lets the tint's alpha byte dim
that layer (base and unflagged layers apply fully). `DynamicTextures.register(id, image)` makes
the result bindable. Both are optional helpers, not part of the render core.

**Rendering backend.** GPU skinning is the default. On a per-model bone-budget overflow
(> 16 bones) that model alone falls back to CPU; on a device/pipeline error the whole path
latches to CPU for the session. Iris/shader packs are detected at runtime and route through
the CPU path (which shader packs light correctly). Inspect the current state via
`BlackVertexStatus.backend` and `.fallbackReason` if you want to surface it to the player — the
library itself only logs.

## Model formats

| Format             | Extension     | Supported                                                                                   |
|--------------------|---------------|---------------------------------------------------------------------------------------------|
| Blockbuster OBJ    | `.bobj`       | mesh + armature + keyframe clips (LINEAR and BEZIER interpolation)                          |
| GeckoLib / Bedrock | `.geo.json`   | bones, box-UV cubes, inflate; animations via `GeoAnimations.parse` (box UV only, XYZ euler) |
| Cuboids            | *(generated)* | `CuboidMesh.mesh` / `CuboidMesh.model`, Blockbench pixel conventions                        |

A GeckoLib animation file is loaded separately and merged onto a model:

```kotlin
val animated = model.withAnimations(GeoAnimations.parse(animationJson, model.skeleton))
```

## Not tested features

- I didn't test position for attachment presets, other than LOWER_BACK and HEAD_TOP
- I didn't test the GeckoLib format parser on real models

(Report if you find any issues, pls)

## Flags

| Property                    | Effect                                                                                  |
|-----------------------------|-----------------------------------------------------------------------------------------|
| `-Dblackvertex.backend=cpu` | Force CPU skinning (debugging / comparison)                                             |
| `-Dblackvertex.backend=gpu` | Force GPU skinning even when Iris is installed (default: fall back to CPU under packs)  |
| `-Dblackvertex.demo=true`   | Demo cosmetics on every player + `/blackvertex-stress` and `/blackvertex-tune` commands |

## Building

```
./gradlew build
```

JDK 25. Unit tests cover the parsers, pose sampling and CPU skinning — all Minecraft-free, run
off the game as plain JVM tests.

## Issues

- Report any issues, please. I'll try to fix them.
- They are defenetly there, i didnt test everything, because im laaaaaazy.

- Also, you can contribute too. Fork the project, change everything you want and make a PR.

## License

MIT — see [LICENSE.txt](LICENSE.txt).
