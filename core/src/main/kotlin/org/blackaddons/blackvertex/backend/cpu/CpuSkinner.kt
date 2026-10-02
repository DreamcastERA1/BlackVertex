package org.blackaddons.blackvertex.backend.cpu

import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Vertex
import org.joml.Matrix4f
import kotlin.math.sqrt

// CPU skinning: transforms a vertex by its weighted bone matrices — the fallback twin of
// the GPU vertex shader's blend.
@InternalBlackVertexApi
object CpuSkinner {

    fun skin(v: Vertex, palette: Array<Matrix4f>, out: FloatArray, at: Int) {
        val x = v.position.x; val y = v.position.y; val z = v.position.z
        val nx = v.normal.x; val ny = v.normal.y; val nz = v.normal.z
        var px = 0f; var py = 0f; var pz = 0f
        var qx = 0f; var qy = 0f; var qz = 0f
        for (k in 0 until Vertex.MAX_INFLUENCES) {
            val w = v.boneWeights[k]
            if (w == 0f) continue
            val m = palette[v.boneIndices[k]]
            px += w * (m.m00() * x + m.m10() * y + m.m20() * z + m.m30())
            py += w * (m.m01() * x + m.m11() * y + m.m21() * z + m.m31())
            pz += w * (m.m02() * x + m.m12() * y + m.m22() * z + m.m32())
            qx += w * (m.m00() * nx + m.m10() * ny + m.m20() * nz)
            qy += w * (m.m01() * nx + m.m11() * ny + m.m21() * nz)
            qz += w * (m.m02() * nx + m.m12() * ny + m.m22() * nz)
        }
        val len2 = qx * qx + qy * qy + qz * qz
        if (len2 > 1e-12f) {
            val inv = 1f / sqrt(len2)
            qx *= inv; qy *= inv; qz *= inv
        }
        out[at] = px; out[at + 1] = py; out[at + 2] = pz
        out[at + 3] = qx; out[at + 4] = qy; out[at + 5] = qz
    }
}
