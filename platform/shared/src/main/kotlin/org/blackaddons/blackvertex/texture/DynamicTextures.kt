package org.blackaddons.blackvertex.texture

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * Registers baked [NativeImage]s as bindable textures. Optional helper (not render core).
 * Call on the client thread once the game has started (texture upload happens on bind).
 */
object DynamicTextures {

    /**
     * Register [image] under [id], replacing any previous texture there. The [DynamicTexture]
     * takes ownership of the image. Returns [id] for convenience.
     */
    fun register(id: Identifier, image: NativeImage): Identifier {
        val texture = DynamicTexture({ id.toString() }, image)
        Minecraft.getInstance().textureManager.register(id, texture)
        return id
    }
}
