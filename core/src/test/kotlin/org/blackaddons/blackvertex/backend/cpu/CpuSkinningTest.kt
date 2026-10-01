package org.blackaddons.blackvertex.backend.cpu

import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Vertex
import org.blackaddons.blackvertex.format.bobj.BobjParser
import org.blackaddons.blackvertex.format.cuboid.CuboidMesh
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(InternalBlackVertexApi::class)
class CpuSkinningTest {

    /** The triangles a QUADS draw makes of [quads] (a,b,c,d -> abc, cda), degenerate ones dropped. */
    private fun drawnTriangles(quads: IntArray): Set<List<Int>> = buildSet {
        for (q in quads.indices step 4) {
            val (a, b, c, d) = listOf(quads[q], quads[q + 1], quads[q + 2], quads[q + 3])
            add(canonical(a, b, c))
            if (d != c) add(canonical(c, d, a))
        }
    }

    private fun triangles(indices: IntArray): Set<List<Int>> =
        (indices.indices step 3).map { canonical(indices[it], indices[it + 1], indices[it + 2]) }.toSet()

    /** Same triangle, same winding, whatever corner it starts at. */
    private fun canonical(a: Int, b: Int, c: Int): List<Int> =
        listOf(listOf(a, b, c), listOf(b, c, a), listOf(c, a, b)).minBy { it[0] }

    private fun assertDrawsSameTriangles(indices: IntArray) {
        val packed = QuadPacking.pack(indices)
        assertEquals(0, packed.size % 4)
        assertEquals(triangles(indices), drawnTriangles(packed))
    }

    @Test
    fun `a cuboid packs into six real quads drawing its twelve triangles`() {
        val mesh = CuboidMesh.mesh("box", Vector3f(), Vector3f(8f, 8f, 8f), uv = 0 to 0, textureWidth = 64, textureHeight = 64)
        val packed = QuadPacking.pack(mesh.indices)
        assertEquals(24, packed.size, "one quad per face instead of two degenerate ones")
        assertDrawsSameTriangles(mesh.indices)
    }

    @Test
    fun `fan triangulated polygons and loose triangles keep every triangle`() {
        assertDrawsSameTriangles(intArrayOf(0, 1, 2, 0, 2, 3, 0, 3, 4)) // pentagon fan: a quad and a leftover
        assertDrawsSameTriangles(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)) // nothing pairs
        assertDrawsSameTriangles(intArrayOf(5, 6, 7, 7, 8, 5)) // pair given in another rotation
    }

    @Test
    fun `an exported model pairs most triangles across the mesh and draws the same ones`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/models/tail/model.bobj")).bufferedReader().readText()
        for (mesh in BobjParser.parse(text).meshes) {
            assertDrawsSameTriangles(mesh.indices)
            val oldVertices = mesh.indices.size / 3 * 4
            assertTrue(mesh.quadIndices.size <= oldVertices * 0.6, "${mesh.name}: ${mesh.quadIndices.size} of $oldVertices")
        }
    }

    @Test
    fun `single pass skinning matches per influence JOML transforms`() {
        val a = Matrix4f().translate(1f, 2f, 3f).rotateY(0.7f).scale(1.5f)
        val b = Matrix4f().rotateX(-1.1f).translate(-0.5f, 0f, 2f)
        val palette = arrayOf(Matrix4f(), a, b)
        val v = Vertex(Vector3f(0.3f, -0.2f, 0.9f), Vector3f(0f, 1f, 0f).normalize(), 0f, 0f, intArrayOf(1, 2, 0, 0), floatArrayOf(0.75f, 0.25f, 0f, 0f))

        val out = FloatArray(6)
        CpuSkinner.skin(v, palette, out, 0)

        val pos = Vector3f(v.position).let { a.transformPosition(it, Vector3f()).mul(0.75f) }
            .add(b.transformPosition(Vector3f(v.position), Vector3f()).mul(0.25f))
        val normal = a.transformDirection(Vector3f(v.normal), Vector3f()).mul(0.75f)
            .add(b.transformDirection(Vector3f(v.normal), Vector3f()).mul(0.25f)).normalize()
        assertEquals(pos.x, out[0], 1e-5f); assertEquals(pos.y, out[1], 1e-5f); assertEquals(pos.z, out[2], 1e-5f)
        assertEquals(normal.x, out[3], 1e-5f); assertEquals(normal.y, out[4], 1e-5f); assertEquals(normal.z, out[5], 1e-5f)
    }
}
