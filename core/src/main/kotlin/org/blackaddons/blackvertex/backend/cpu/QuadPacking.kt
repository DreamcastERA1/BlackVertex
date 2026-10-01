package org.blackaddons.blackvertex.backend.cpu

import org.blackaddons.blackvertex.api.InternalBlackVertexApi

/**
 * Re-groups triangle indices into the 4-corner groups Minecraft's QUADS topology draws.
 *
 * A QUADS draw splits `q0,q1,q2,q3` into `(q0,q1,q2),(q2,q3,q0)` - two triangles across the
 * `q0-q2` diagonal. So any two triangles sharing an edge (walked in opposite directions, i.e. same
 * facing) go out as one quad with that edge as the diagonal, drawing exactly those two triangles
 * with half the vertices of two degenerate quads. A triangle with no free neighbour stays a
 * degenerate quad (`a,b,c,c`). Pairs are found across the whole mesh, not just consecutive
 * triangles: exported models rarely list neighbours next to each other, and draw order doesn't
 * matter here (translucent quads are re-sorted on upload).
 */
@InternalBlackVertexApi
object QuadPacking {

    fun pack(indices: IntArray): IntArray {
        require(indices.size % 3 == 0) { "triangle indices come in threes, got ${indices.size}" }
        val triangles = indices.size / 3
        // Directed edge -> first triangle walking it.
        val byEdge = HashMap<Long, Int>(triangles * 4)
        for (t in 0 until triangles) {
            for (e in 0 until 3) byEdge.putIfAbsent(edge(indices[t * 3 + e], indices[t * 3 + (e + 1) % 3]), t)
        }

        val used = BooleanArray(triangles)
        val out = IntArray(triangles * 4)
        var o = 0
        for (t in 0 until triangles) {
            if (used[t]) continue
            used[t] = true
            var paired = false
            for (e in 0 until 3) {
                // This triangle walks u -> v with w opposite; its neighbour across u-v walks v -> u.
                val u = indices[t * 3 + e]
                val v = indices[t * 3 + (e + 1) % 3]
                val w = indices[t * 3 + (e + 2) % 3]
                val other = byEdge[edge(v, u)] ?: continue
                if (used[other]) continue
                val d = (0 until 3).map { indices[other * 3 + it] }.firstOrNull { it != u && it != v } ?: continue
                used[other] = true
                // (v,w,u) is this triangle, (u,d,v) the neighbour: quad v,w,u,d.
                out[o++] = v; out[o++] = w; out[o++] = u; out[o++] = d
                paired = true
                break
            }
            if (!paired) {
                out[o++] = indices[t * 3]; out[o++] = indices[t * 3 + 1]
                out[o++] = indices[t * 3 + 2]; out[o++] = indices[t * 3 + 2]
            }
        }
        return out.copyOf(o)
    }

    private fun edge(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xFFFFFFFFL)
}
