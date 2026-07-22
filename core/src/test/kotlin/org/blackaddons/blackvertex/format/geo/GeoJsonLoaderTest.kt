package org.blackaddons.blackvertex.format.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoJsonLoaderTest {

    private val json = """
    {
      "format_version": "1.12.0",
      "minecraft:geometry": [{
        "description": {"identifier": "geometry.test", "texture_width": 64, "texture_height": 64},
        "bones": [
          {"name": "root", "pivot": [0, 0, 0],
           "cubes": [{"origin": [-4, 0, -4], "size": [8, 8, 8], "uv": [0, 0]}]},
          {"name": "top", "parent": "root", "pivot": [0, 8, 0], "rotation": [0, 45, 0],
           "cubes": [{"origin": [-2, 8, -2], "size": [4, 4, 4], "uv": [32, 0], "inflate": 0.5}]}
        ]
      }]
    }
    """.trimIndent()

    @Test
    fun `parses bones and cubes`() {
        val model = GeoJsonLoader.load(json)
        assertEquals(2, model.skeleton.boneCount)
        assertEquals(0, model.skeleton.bones[1].parent)
        assertEquals(2, model.meshes.size)
        assertEquals(48, model.meshes.sumOf { it.vertices.size })
        // Second cube rides bone 1.
        assertTrue(model.meshes[1].vertices.all { it.boneIndices[0] == 1 })
        // 45° rest rotation is baked: side normals leave the axis-aligned grid.
        assertTrue(model.meshes[1].vertices.any { v -> v.normal.y == 0f && kotlin.math.abs(v.normal.x) in 0.5f..0.9f })
    }
}
