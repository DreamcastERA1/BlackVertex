package org.blackaddons.blackvertex.backend.gpu

import com.mojang.blaze3d.systems.RenderSystem
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.compat.GpuBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

// A Model kept on the GPU: bind pose vertices, bone influences and a triangle index buffer, uploaded once. Per frame
// only the palette UBO changes. It uses real TRIANGLES topology: the degenerate quad trick is a limit of the vanilla
// pipeline that our own pipeline does not have.
//
// The upload and packing are version-neutral (shared). The vertex FORMAT is not here: it is built per platform (26.2
// and 26.3 through GpuFormat, 26.1.2 through VertexFormatElement) and lives next to each pipeline.
internal class GpuMesh private constructor(
    val vertexBuffer: GpuBuffer,
    val indexBuffer: GpuBuffer,
    val indexCount: Int,
    val boneCount: Int,
) : org.blackaddons.blackvertex.render.gpu.GpuMeshHandle {

    fun close() {
        vertexBuffer.close()
        indexBuffer.close()
    }

    companion object {
        // The bone limit baked into the shader (the MAX_BONES define sizes the palette UBO).
        const val MAX_BONES = 16

        // Bytes per vertex: position 3f, UV 2f, normal 3f, indices 4 x u8 and weights 4 x un8.
        private const val VERTEX_BYTES = 12 + 8 + 12 + 4 + 4

        // Render thread only, because it touches the device. Returns null when the model exceeds the shader limits, and
        // the caller then falls back to the CPU path.
        fun upload(model: Model, name: String): GpuMesh? {
            val boneCount = model.skeleton.boneCount
            if (boneCount > MAX_BONES) return null
            val vertexCount = model.meshes.sumOf { it.vertices.size }
            val indexCount = model.meshes.sumOf { it.indices.size }
            if (vertexCount == 0 || indexCount == 0) return null

            val vertices = ByteBuffer.allocateDirect(vertexCount * VERTEX_BYTES).order(ByteOrder.nativeOrder())
            val indices = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder())

            var base = 0
            for (mesh in model.meshes) {
                for (v in mesh.vertices) {
                    vertices.putFloat(v.position.x).putFloat(v.position.y).putFloat(v.position.z)
                    vertices.putFloat(v.u).putFloat(v.v)
                    vertices.putFloat(v.normal.x).putFloat(v.normal.y).putFloat(v.normal.z)
                    for (k in 0 until 4) vertices.put(v.boneIndices[k].toByte())
                    putQuantizedWeights(vertices, v.boneWeights)
                }
                for (i in mesh.indices) indices.putInt(base + i)
                base += mesh.vertices.size
            }
            vertices.flip()
            indices.flip()

            val device = RenderSystem.getDevice()
            return GpuMesh(
                device.createBuffer({ "blackvertex mesh '$name' vertices" }, GpuBuffer.USAGE_VERTEX, vertices),
                device.createBuffer({ "blackvertex mesh '$name' indices" }, GpuBuffer.USAGE_INDEX, indices),
                indexCount,
                boneCount,
            )
        }

        // Rounding each weight on its own can sum to 252..256 out of 255, which visibly scales the skin matrix. A
        // largest remainder distribution keeps the UNORM weights summing to exactly 255 (1.0).
        private fun putQuantizedWeights(out: ByteBuffer, w: FloatArray) {
            val q = IntArray(4) { (w[it] * 255f).toInt() }
            var deficit = 255 - q.sum()
            while (deficit > 0) {
                var best = 0
                var bestRem = -1f
                for (k in 0 until 4) {
                    val rem = w[k] * 255f - q[k]
                    if (rem > bestRem) { bestRem = rem; best = k }
                }
                q[best]++
                deficit--
            }
            for (k in 0 until 4) out.put(q[k].coerceIn(0, 255).toByte())
        }
    }
}
