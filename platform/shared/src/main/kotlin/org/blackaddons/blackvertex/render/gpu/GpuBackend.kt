package org.blackaddons.blackvertex.render.gpu

import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Model
import org.joml.Matrix4f

/** How a cosmetic draw blends and is lit, like vanilla's entityCutout, entityTranslucent and their emissive variant. */
internal enum class DrawMode { CUTOUT, TRANSLUCENT, EMISSIVE }

/** An opaque per-model GPU upload owned by a [GpuBackend]. The layer only passes it back to [GpuBackend.submit]. */
internal interface GpuMeshHandle

/**
 * The version-specific GPU skinning path behind one interface, so the shared feature layer stays version-neutral. Each
 * platform (26.2, 26.3) supplies its own implementation and installs it in [BlackVertexGpu]. The blaze3d device
 * works the same across versions; what differs, and what this hides, is each version's spelling of the pipeline,
 * transform and texture setup. Both inject the draw through Fabric's feature renderer submit.
 */
@OptIn(InternalBlackVertexApi::class)
internal interface GpuBackend {

    /** Registers the pipelines and the injection hook. Called once from client init. */
    fun init()

    /**
     * True when the GPU path can be used this frame: it has not failed or been forced off, and no shader pack is
     * active.
     */
    fun usable(): Boolean

    /** Uploads the static buffers for a model once and returns them, or null when the model cannot use the GPU path. */
    fun meshFor(model: Model, textureKey: String): GpuMeshHandle?

    /**
     * Queues one skinned cosmetic draw for this frame. [mesh] must be one that this backend returned. [collector] is
     * the vanilla submission sink of the current frame; the backend routes its draw node through it.
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

    /** Drops the GPU buffers of models that are no longer in [stillUsed]. Called after a cosmetic is removed. */
    fun releaseUnused(stillUsed: Collection<Model>)
}

/** Holds the platform's [GpuBackend], installed at client init. Null means there is no GPU path, only the CPU one. */
internal object BlackVertexGpu {
    @Volatile
    var backend: GpuBackend? = null
}
