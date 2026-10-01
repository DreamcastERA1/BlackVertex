package org.blackaddons.blackvertex.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.backend.cpu.CpuSkinner

// CPU-path glue: skins on the CPU and emits standard entity-format vertices through
// submitCustomGeometry, so vanilla lighting/overlay/outline apply for free.
@OptIn(InternalBlackVertexApi::class)
internal object BlackVertexRenderer {

    fun submit(
        model: Model,
        palette: PosePalette,
        renderType: RenderType,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        light: Int,
        overlay: Int,
        argb: Int,
    ) {
        collector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            emit(model, palette, pose, vc, light, overlay, argb)
        }
    }

    private fun emit(
        model: Model,
        palette: PosePalette,
        pose: PoseStack.Pose,
        vc: VertexConsumer,
        light: Int,
        overlay: Int,
        argb: Int,
    ) {
        val palettes = palette.matrices
        for (mesh in model.meshes) {
            val verts = mesh.vertices
            // Skin each vertex once: a corner is shared by up to ~6 triangles, and emitting re-reads it.
            val skinned = scratch(verts.size * 6)
            for (i in verts.indices) CpuSkinner.skin(verts[i], palettes, skinned, i * 6)
            // Entity pipelines draw QUADS; quadIndices pairs each fan-triangulated quad back up and
            // pads a lone triangle as a degenerate quad (a,b,c,c).
            for (index in mesh.quadIndices) {
                val v = verts[index]
                val o = index * 6
                vc.addVertex(pose, skinned[o], skinned[o + 1], skinned[o + 2])
                    .setColor(argb)
                    .setUv(v.u, v.v)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, skinned[o + 3], skinned[o + 4], skinned[o + 5])
            }
        }
    }

    // Per thread, because submit callbacks may run off the render thread; grown, never shrunk.
    private val scratchHolder = ThreadLocal.withInitial { FloatArray(0) }

    private fun scratch(size: Int): FloatArray {
        val current = scratchHolder.get()
        if (current.size >= size) return current
        return FloatArray(size).also(scratchHolder::set)
    }
}
