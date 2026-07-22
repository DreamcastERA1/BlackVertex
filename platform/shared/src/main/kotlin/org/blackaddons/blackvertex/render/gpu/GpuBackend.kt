package org.blackaddons.blackvertex.render.gpu

import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Model
import org.joml.Matrix4f

/** How a cosmetic draw blends and lights — mirrors vanilla entityCutout / entityTranslucent / …Emissive. */
internal enum class DrawMode { CUTOUT, TRANSLUCENT, EMISSIVE }

/** Opaque per-model GPU upload owned by a [GpuBackend]; the layer only shuttles it back to [GpuBackend.submit]. */
internal interface GpuMeshHandle

/**
 * The version-specific GPU skinning path behind one seam, so the shared feature layer stays
 * version-neutral. Each platform (26.2, 26.1.2) supplies its own implementation and installs it in
 * [BlackVertexGpu]. The blaze3d device is the same across versions; what differs — and what this
 * hides — is the frame injection: 26.2 rides Fabric's feature-renderer submit, 26.1.2 a mixin pass.
 */
@OptIn(InternalBlackVertexApi::class)
internal interface GpuBackend {

    /** Register pipelines and the injection hook. Called once from client init. */
    fun init()

    /** GPU path usable this frame: not failed/forced off and no shader pack active. */
    fun usable(): Boolean

    /** Uploads (once) and returns the static buffers for a model, or null when it's GPU-ineligible. */
    fun meshFor(model: Model, textureKey: String): GpuMeshHandle?

    /**
     * Queue one skinned cosmetic draw for this frame. [mesh] must be one this backend returned.
     * [collector] is the current frame's vanilla submission sink: the submit-based backend (26.2)
     * routes its draw node through it; mixin-driven backends (26.1.2) enqueue internally and ignore it.
     */
    fun submit(
        collector: SubmitNodeCollector,
        mesh: GpuMeshHandle,
        palette: PosePalette,
        pose: Matrix4f,
        texture: Identifier,
        light: Int,
        overlay: Int,
        mode: DrawMode,
        color: Int,
    )

    /** Drops GPU buffers of models no longer in [stillUsed]. Called after a cosmetic is removed. */
    fun releaseUnused(stillUsed: Collection<Model>)
}

/** Holds the platform's [GpuBackend], installed at client init. Null = no GPU path (CPU only). */
internal object BlackVertexGpu {
    @Volatile
    var backend: GpuBackend? = null
}
