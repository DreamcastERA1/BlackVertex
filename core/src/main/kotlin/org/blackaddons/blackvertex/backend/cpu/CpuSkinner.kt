package org.blackaddons.blackvertex.backend.cpu

import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Vertex
import org.joml.Matrix4f
import org.joml.Vector3f

// CPU skinning: transforms a vertex by its weighted bone matrices — the fallback twin of
// the GPU vertex shader's blend. Not thread-safe: one instance per job (reused scratch).
@InternalBlackVertexApi
class CpuSkinner {

    private val acc = Vector3f()
    private val tmp = Vector3f()

    // Writes the skinned model-space position of v into out and returns it.
    fun position(v: Vertex, palette: Array<Matrix4f>, out: Vector3f): Vector3f {
        acc.zero()
        for (k in 0 until Vertex.MAX_INFLUENCES) {
            val w = v.boneWeights[k]
            if (w == 0f) continue
            tmp.set(v.position)
            palette[v.boneIndices[k]].transformPosition(tmp)
            acc.add(tmp.x * w, tmp.y * w, tmp.z * w)
        }
        return out.set(acc)
    }

    // Writes the skinned, normalized model-space normal of v into out and returns it.
    fun normal(v: Vertex, palette: Array<Matrix4f>, out: Vector3f): Vector3f {
        acc.zero()
        for (k in 0 until Vertex.MAX_INFLUENCES) {
            val w = v.boneWeights[k]
            if (w == 0f) continue
            tmp.set(v.normal)
            palette[v.boneIndices[k]].transformDirection(tmp) // rotation/scale only, no translation
            acc.add(tmp.x * w, tmp.y * w, tmp.z * w)
        }
        if (acc.lengthSquared() > 1e-12f) acc.normalize()
        return out.set(acc)
    }
}
