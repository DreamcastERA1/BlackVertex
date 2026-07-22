package org.blackaddons.blackvertex.format.cuboid

import org.blackaddons.blackvertex.api.model.Mesh
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.api.model.Skeleton
import org.blackaddons.blackvertex.api.model.Vertex
import org.joml.Vector3f

/**
 * Generates cuboid meshes with the standard Minecraft/Blockbench box-UV unwrap.
 * Coordinates and UVs are in PIXELS (16 px = 1 block), matching Blockbench and
 * GeckoLib `.geo.json` conventions; output positions are converted to block units.
 */
object CuboidMesh {

    /**
     * One cuboid as a [Mesh] (24 vertices / 12 triangles, per-face normals).
     *
     * @param from/to  opposite corners in pixels, model space
     * @param uv       box-unwrap origin (pixels) on the texture
     * @param textureWidth/Height texture size in pixels, normalizes UVs
     * @param inflate  expands all faces outward by this many pixels (Blockbench "inflate")
     * @param bone     skeleton bone index all vertices ride (0 = root for static cuboids)
     */
    fun mesh(
        name: String,
        from: Vector3f,
        to: Vector3f,
        uv: Pair<Int, Int>,
        textureWidth: Int,
        textureHeight: Int,
        inflate: Float = 0f,
        bone: Int = 0,
    ): Mesh {
        val x0 = minOf(from.x, to.x) - inflate; val x1 = maxOf(from.x, to.x) + inflate
        val y0 = minOf(from.y, to.y) - inflate; val y1 = maxOf(from.y, to.y) + inflate
        val z0 = minOf(from.z, to.z) - inflate; val z1 = maxOf(from.z, to.z) + inflate
        // UV cell sizes come from the UN-inflated box, like Blockbench.
        val w = maxOf(from.x, to.x) - minOf(from.x, to.x)
        val h = maxOf(from.y, to.y) - minOf(from.y, to.y)
        val d = maxOf(from.z, to.z) - minOf(from.z, to.z)
        val u = uv.first.toFloat()
        val v = uv.second.toFloat()

        val vertices = ArrayList<Vertex>(24)
        val indices = IntArray(36)
        var vi = 0
        var ii = 0

        // Emits one face: 4 corners CCW as seen from outside, one UV rect (top-down v).
        fun face(nx: Float, ny: Float, nz: Float, corners: Array<FloatArray>, u0: Float, v0: Float, u1: Float, v1: Float) {
            val uvs = arrayOf(u0 to v0, u1 to v0, u1 to v1, u0 to v1)
            val base = vi
            for (k in 0 until 4) {
                val c = corners[k]
                vertices.add(
                    Vertex(
                        Vector3f(c[0] / 16f, c[1] / 16f, c[2] / 16f),
                        Vector3f(nx, ny, nz),
                        uvs[k].first / textureWidth, uvs[k].second / textureHeight,
                        intArrayOf(bone, 0, 0, 0), floatArrayOf(1f, 0f, 0f, 0f),
                    )
                )
                vi++
            }
            for (t in intArrayOf(0, 1, 2, 0, 2, 3)) indices[ii++] = base + t
        }

        // Box unwrap: [d][w][d][w] columns, top row (v..v+d) = up/down, bottom (v+d..v+d+h) = sides.
        face(0f, 1f, 0f, arrayOf(floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0)), u + d, v + d, u + d + w, v) // up
        face(0f, -1f, 0f, arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)), u + d + w, v + d, u + d + w + w, v) // down
        face(0f, 0f, -1f, arrayOf(floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0)), u + d, v + d, u + d + w, v + d + h) // north
        face(0f, 0f, 1f, arrayOf(floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)), u + d + w + d, v + d, u + d + w + d + w, v + d + h) // south
        face(-1f, 0f, 0f, arrayOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y0, z0)), u, v + d, u + d, v + d + h) // west
        face(1f, 0f, 0f, arrayOf(floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1)), u + d + w, v + d, u + d + w + d, v + d + h) // east

        return Mesh(name, vertices, indices, texture = null)
    }

    /** Wraps [meshes] into a static [Model] with a single identity root bone. */
    fun model(vararg meshes: Mesh): Model =
        Model(meshes.toList(), Skeleton.singleRoot(), emptyMap())
}
