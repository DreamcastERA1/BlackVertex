package org.blackaddons.blackvertex.format.bobj

import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.backend.cpu.CpuSkinner
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Parser validation against real Blockbuster .bobj exports in test resources.
 *
 * These tests touch no Minecraft classes — parser, model, pose sampling and CPU
 * skinning are all engine-independent, so they run as plain JVM unit tests.
 */
class BobjParserTest {

    private fun load(name: String): Model {
        val stream = requireNotNull(javaClass.getResourceAsStream("/models/$name/model.bobj")) {
            "missing test resource: /models/$name/model.bobj"
        }
        return BobjParser.parse(stream.bufferedReader().readText())
    }

    @Test
    fun `parses ears geometry, skeleton and clip`() {
        val m = load("ears")
        assertEquals(280, m.meshes.sumOf { it.vertices.size }, "vertex count")
        assertEquals(484 * 3, m.meshes.sumOf { it.indices.size }, "triangle indices")
        assertEquals(4, m.skeleton.boneCount, "bone count")
        assertEquals(setOf("idle"), m.animations.keys, "clips")
    }

    @Test
    fun `parses tail geometry, skeleton and clips`() {
        val m = load("tail")
        assertEquals(729, m.meshes.sumOf { it.vertices.size }, "vertex count")
        assertEquals(1262 * 3, m.meshes.sumOf { it.indices.size }, "triangle indices")
        assertEquals(5, m.skeleton.boneCount, "bone count")
        assertEquals(setOf("idle", "idlealt"), m.animations.keys, "clips")
    }

    @Test
    fun `every vertex weight set is normalized`() {
        for (name in listOf("ears", "tail")) {
            val bad = load(name).meshes.flatMap { it.vertices }
                .count { abs(it.boneWeights.sum() - 1f) > 1e-3f }
            assertEquals(0, bad, "$name: vertices whose weights don't sum to 1")
        }
    }

    @OptIn(InternalBlackVertexApi::class)
    @Test
    fun `skinning an animated pose moves vertices without exploding`() {
        val m = load("tail")
        val clip = requireNotNull(m.animations["idle"])
        val palette = PosePalette(m.skeleton)
        val v = m.meshes.first().vertices.first { it.boneWeights[0] > 0f }
        val out = FloatArray(6)
        fun skinnedPosition(): Vector3f {
            CpuSkinner.skin(v, palette.matrices, out, 0)
            return Vector3f(out[0], out[1], out[2])
        }

        palette.update(null, 0f)
        val bind = skinnedPosition()
        palette.update(clip, clip.durationSeconds * 0.5f)
        val moved = skinnedPosition()

        assertTrue(bind.isFinite && moved.isFinite, "skinned positions must be finite")
        assertTrue(bind.distance(moved) > 1e-5f, "animation should displace the vertex")
    }
}
