package org.blackaddons.blackvertex.api.model

import org.blackaddons.blackvertex.api.model.Vertex.Companion.MAX_INFLUENCES
import org.joml.Vector3f

/** One drawable submesh (a `o` object in the .bobj). Shares the model's skeleton. */
class Mesh(
    val name: String,
    /** Flat, de-duplicated vertex list. [indices] references into this. */
    val vertices: List<Vertex>,
    /** Triangle indices, 3 per face (quads are triangulated at parse time). */
    val indices: IntArray,
    /** Texture path as declared in the source file, if any. Resolved by the consumer. */
    val texture: String?,
)

/**
 * A single vertex with up to [MAX_INFLUENCES] bone influences.
 * [u]/[v] use Minecraft's convention: v=0 is the TOP of the texture (loaders convert
 * from bottom-up source formats like OBJ at parse time).
 * [boneWeights] are normalized to sum to 1; unused slots have index 0 / weight 0.
 * An unskinned vertex has one influence on a synthetic root bone with weight 1.
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
