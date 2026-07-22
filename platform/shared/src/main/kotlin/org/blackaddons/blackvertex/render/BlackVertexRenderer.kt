package org.blackaddons.blackvertex.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.api.model.Vertex
import org.blackaddons.blackvertex.backend.cpu.CpuSkinner
import org.joml.Vector3f

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
        // Local scratch: submit callbacks may run off the render thread, so keep no shared state.
        val skinner = CpuSkinner()
        val p = Vector3f()
        val n = Vector3f()
        val palettes = palette.matrices

        fun vertex(v: Vertex) {
            skinner.position(v, palettes, p)
            skinner.normal(v, palettes, n)
            vc.addVertex(pose, p.x, p.y, p.z)
                .setColor(argb)
                .setUv(v.u, v.v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, n.x, n.y, n.z)
        }

        for (mesh in model.meshes) {
            val verts = mesh.vertices
            val idx = mesh.indices
            // Entity pipelines draw QUADS, so each triangle is emitted as a degenerate quad
            // (last vertex repeated: v0,v1,v2,v2). Feeding raw triangles mis-groups the stream.
            var i = 0
            while (i < idx.size) {
                val a = verts[idx[i]]
                val b = verts[idx[i + 1]]
                val c = verts[idx[i + 2]]
                vertex(a); vertex(b); vertex(c); vertex(c)
                i += 3
            }
        }
    }
}
