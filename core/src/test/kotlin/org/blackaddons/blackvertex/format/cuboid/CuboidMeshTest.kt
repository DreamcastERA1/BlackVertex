package org.blackaddons.blackvertex.format.cuboid

import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CuboidMeshTest {

    private val mesh = CuboidMesh.mesh(
        "box", Vector3f(0f, 0f, 0f), Vector3f(8f, 8f, 8f),
        uv = 0 to 0, textureWidth = 64, textureHeight = 64,
    )

    @Test
    fun `geometry is a closed 24-vertex box in block units`() {
        assertEquals(24, mesh.vertices.size)
        assertEquals(36, mesh.indices.size)
        for (v in mesh.vertices) {
            assertTrue(v.position.x in 0f..0.5f && v.position.y in 0f..0.5f && v.position.z in 0f..0.5f)
            assertTrue(abs(v.normal.length() - 1f) < 1e-6f, "unit normal")
        }
        // Every edge shared by exactly two triangles = closed manifold.
        val edges = HashMap<Long, Int>()
        for (i in mesh.indices.indices step 3) {
            for (e in 0 until 3) {
                val a = mesh.indices[i + e]; val b = mesh.indices[i + (e + 1) % 3]
                val va = mesh.vertices[a].position; val vb = mesh.vertices[b].position
                fun kx(v: Vector3f) = (v.x * 1000).toLong() * 1_000_000 + (v.y * 1000).toLong() * 1000 + (v.z * 1000).toLong()
                val k = minOf(kx(va), kx(vb)) * 1_000_000_000 + maxOf(kx(va), kx(vb))
                edges.merge(k, 1, Int::plus)
            }
        }
        assertTrue(edges.values.all { it == 2 }, "closed manifold")
    }

    @Test
    fun `uvs stay inside the box-unwrap region`() {
        // 8x8x8 box at uv(0,0): unwrap spans 32x16 px of a 64x64 texture.
        for (v in mesh.vertices) {
            assertTrue(v.u in 0f..0.5f, "u=${v.u}")
            assertTrue(v.v in 0f..0.25f, "v=${v.v}")
        }
    }

    @Test
    fun `model wraps meshes with an identity root bone`() {
        val model = CuboidMesh.model(mesh)
        assertEquals(1, model.skeleton.boneCount)
        assertEquals(24, model.meshes.sumOf { it.vertices.size })
    }
}
