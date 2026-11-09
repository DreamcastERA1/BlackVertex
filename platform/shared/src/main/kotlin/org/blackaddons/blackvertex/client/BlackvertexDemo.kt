package org.blackaddons.blackvertex.client

import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.api.attach.AttachmentPoint
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.format.ModelFormats
import org.blackaddons.blackvertex.render.PlayerCosmetic
import org.blackaddons.blackvertex.render.PlayerCosmetics
import org.blackaddons.blackvertex.texture.DynamicTextures
import org.blackaddons.blackvertex.texture.TextureCompositor

/**
 * A version-neutral demo, enabled by `-Dblackvertex.demo=true`: a tail (lower back) and ears (head) on every player,
 * with textures composited on the fly from the layered source art, the same way production does it. It runs on every MC
 * version, with whichever render path (GPU or CPU) the platform installed.
 *
 * Not gated on `isDevelopment`, because that would switch it on when the library ships inside the parent mod. The
 * command tools (`/blackvertex-*`) live in DemoStress and DemoTune.
 */
internal object BlackvertexDemo {

    val ENABLED: Boolean = System.getProperty("blackvertex.demo")?.toBoolean() == true

    /** Adds the tail and ears cosmetics and schedules their composited textures. Call it once at init. */
    fun setup() {
        // The tail: a composited dynamic texture and a glowing cyan tip, which exercises the emissive pass.
        loadModel("/assets/blackvertex/models/tail/model.bobj")?.let { tail ->
            ClientLifecycleEvents.CLIENT_STARTED.register(
                ClientLifecycleEvents.ClientStarted {
                    DynamicTextures.register(TAIL_TEXTURE, bakeTailTexture())
                    DynamicTextures.register(TAIL_EMISSIVE, bakeTailEmissive())
                }
            )
            PlayerCosmetics.add(
                PlayerCosmetic(
                    tail,
                    clip = "idle",
                    texture = TAIL_TEXTURE,
                    emissive = TAIL_EMISSIVE,
                    attach = AttachmentPoint.LOWER_BACK.attachment,
                )
            )
        }

        // The ears, composited like in production: the white base tinted with the primary color, plus the inner ear
        // overlay tinted with the accent.
        loadModel("/assets/blackvertex/models/ears/model.bobj")?.let { ears ->
            ClientLifecycleEvents.CLIENT_STARTED.register(
                ClientLifecycleEvents.ClientStarted { DynamicTextures.register(EARS_TEXTURE, bakeEarsTexture()) }
            )
            PlayerCosmetics.add(
                PlayerCosmetic(ears, clip = "idle", texture = EARS_TEXTURE, attach = AttachmentPoint.HEAD_TOP.attachment)
            )
        }
    }

    /** The tail's demo texture: a black fur base with a cyan gradient toward the tip. */
    private fun bakeTailTexture(): NativeImage {
        val base = readImage("/assets/blackvertex/models/tail/base_black.png")
        val overlay = readImage("/assets/blackvertex/models/tail/overlay_gradient.png")
        try {
            return TextureCompositor.compose(
                base = base,
                baseTintArgb = 0xFFFFFFFF.toInt(), // keep the base's own dark fur
                overlays = listOf(TextureCompositor.Layer(overlay, 0xFF3FC7FF.toInt())),
            )
        } finally {
            base.close()
            overlay.close()
        }
    }

    /**
     * The ears' demo texture: the pack's plain white cat base tinted black (primary), plus the inner ear overlay tinted
     * cyan (accent) to match the tail's tip.
     */
    private fun bakeEarsTexture(): NativeImage {
        val base = readImage("/assets/blackvertex/models/ears/base_plain.png")
        val overlay = readImage("/assets/blackvertex/models/ears/overlay_inner.png")
        try {
            return TextureCompositor.compose(
                base = base,
                baseTintArgb = 0xFF262626.toInt(), // primary: near-black fur, keeps the shading
                overlays = listOf(TextureCompositor.Layer(overlay, 0xFF3FC7FF.toInt())),
            )
        } finally {
            base.close()
            overlay.close()
        }
    }

    /** The glow layer: cyan wherever the tip gradient is strong, transparent elsewhere. */
    private fun bakeTailEmissive(): NativeImage {
        val overlay = readImage("/assets/blackvertex/models/tail/overlay_gradient.png")
        overlay.use { overlay ->
            val out = NativeImage(overlay.width, overlay.height, false)
            for (y in 0 until overlay.height) {
                for (x in 0 until overlay.width) {
                    val mask = (overlay.getPixel(x, y) ushr 24) and 0xFF
                    val glow = if (mask > 96) mask else 0 // only the dense part of the tip glows
                    out.setPixel(x, y, (glow shl 24) or 0x3FC7FF)
                }
            }
            return out
        }
    }

    private fun loadModel(path: String): Model? =
        loadText(path)?.let { ModelFormats.load(path.substringAfterLast('/'), it) }

    private fun readImage(path: String): NativeImage {
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }
        return stream.use { NativeImage.read(it) }
    }

    private fun loadText(path: String): String? =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    private val TAIL_TEXTURE: Identifier = Identifier.fromNamespaceAndPath("blackvertex", "dynamic/tail_demo")
    private val EARS_TEXTURE: Identifier = Identifier.fromNamespaceAndPath("blackvertex", "dynamic/ears_demo")
    private val TAIL_EMISSIVE: Identifier = Identifier.fromNamespaceAndPath("blackvertex", "dynamic/tail_demo_emissive")
}
