package org.blackaddons.blackvertex.format.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoAnimationsTest {

    private val geo = GeoJsonLoader.load(
        """{"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},
            "bones":[{"name":"root","pivot":[0,0,0],
                      "cubes":[{"origin":[0,0,0],"size":[4,4,4],"uv":[0,0]}]}]}]}"""
    )

    private val anims = """
    {
      "animations": {
        "animation.test.idle": {
          "loop": true,
          "animation_length": 2.0,
          "bones": {
            "root": {
              "rotation": {"0.0": [0, 0, 0], "1.0": [0, 90, 0], "2.0": {"post": [0, 0, 0]}},
              "position": {"0.0": [0, 0, 0], "1.0": [0, 8, 0]},
              "scale": 1.5
            },
            "ghost": {"rotation": [0, 0, 0]}
          }
        }
      }
    }
    """.trimIndent()

    @Test
    fun `parses clips against the skeleton`() {
        val model = geo.withAnimations(GeoAnimations.parse(anims, geo.skeleton))
        val clip = model.animations.getValue("animation.test.idle")
        assertEquals(2f, clip.durationSeconds)
        val channel = clip.channels.getValue(0)
        assertEquals(3, channel.rotations.size)
        assertEquals(2, channel.translations.size)
        // position pixels -> blocks
        assertEquals(0.5f, channel.translations[1].value.y)
        // static scale becomes one keyframe at t=0
        assertEquals(1, channel.scales.size)
        assertEquals(1.5f, channel.scales[0].value.x)
        assertTrue(clip.channels.size == 1, "unknown bone 'ghost' skipped")
    }
}
