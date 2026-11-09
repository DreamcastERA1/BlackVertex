package org.blackaddons.blackvertex.api.model

import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Vertex.Companion.MAX_INFLUENCES
import org.blackaddons.blackvertex.backend.cpu.QuadPacking
import org.joml.Vector3f

/** One drawable submesh (an `o` object in the .bobj). It shares the model's skeleton. */
class Mesh(
    val name: String,
    /** A flat list of unique vertices. [indices] point into it. */
    val vertices: List<Vertex>,
    /** Triangle indices, 3 per face (quads are split into triangles when parsing). */
    val indices: IntArray,
    /** The texture path as declared in the source file, if any. The consumer resolves it. */
    val texture: String?,
) {
    @OptIn(InternalBlackVertexApi::class)
    val quadIndices: IntArray by lazy { QuadPacking.pack(indices) }
}

/**
 * A single vertex with up to [MAX_INFLUENCES] bone influences. [u] and [v] use Minecraft's convention, where v = 0 is
 * the TOP of the texture (loaders convert bottom-up formats like OBJ when parsing). [boneWeights] are normalized to sum
 * to 1, and unused slots have index 0 and weight 0. An unskinned vertex has one influence, on a synthetic root bone,
 * with weight 1.
 */
class Vertex(
    val position: Vector3f,
    val normal: Vector3f,
    val u: Float,
    val v: Float,
    val boneIndices: IntArray,
    val boneWeights: FloatArray,
) {
    companion object {
        const val MAX_INFLUENCES = 4
    }
}
