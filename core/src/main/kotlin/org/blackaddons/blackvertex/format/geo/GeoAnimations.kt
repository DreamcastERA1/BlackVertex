package org.blackaddons.blackvertex.format.geo

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.blackaddons.blackvertex.api.model.Animation
import org.blackaddons.blackvertex.api.model.BoneChannel
import org.blackaddons.blackvertex.api.model.Keyframe
import org.blackaddons.blackvertex.api.model.Skeleton
import org.joml.Quaternionf
import org.joml.Vector3f
import org.slf4j.LoggerFactory

/**
 * Parser for Bedrock/GeckoLib `.animation.json` clips. Needs the model's [Skeleton] to
 * map bone names to indices: `model.withAnimations(GeoAnimations.parse(text, model.skeleton))`.
 *
 * v1 scope: numeric keyframes only — Molang expressions and GeckoLib easings degrade to
 * a warn + linear/skip, never a crash. Rotation is XYZ euler degrees relative to the rest
 * pose, position is in pixels (/16 to blocks), times are seconds — all straight from the
 * Bedrock conventions.
 */
object GeoAnimations {

    private val LOG = LoggerFactory.getLogger("blackvertex")

    fun parse(text: String, skeleton: Skeleton): Map<String, Animation> {
        val root = JsonParser.parseString(text).asJsonObject
        val animations = root.getAsJsonObject("animations")
            ?: throw IllegalArgumentException("not an animation file (no \"animations\")")

        return animations.entrySet().associate { (name, animEl) ->
            val anim = animEl.asJsonObject
            val channels = HashMap<Int, BoneChannel>()
            var maxTime = anim.get("animation_length")?.asFloat ?: 0f

            anim.getAsJsonObject("bones")?.entrySet()?.forEach { (boneName, boneEl) ->
                val boneIdx = skeleton.indexOf(boneName)
                if (boneIdx < 0) {
                    LOG.warn("BlackVertex: animation '{}' targets unknown bone '{}', skipped", name, boneName)
                    return@forEach
                }
                val bone = boneEl.asJsonObject
                val rotations = track(bone.get("rotation"), name) { t, v ->
                    Keyframe(t, Quaternionf().rotationXYZ(
                        Math.toRadians(v.x.toDouble()).toFloat(),
                        Math.toRadians(v.y.toDouble()).toFloat(),
                        Math.toRadians(v.z.toDouble()).toFloat(),
                    ))
                }
                val positions = track(bone.get("position"), name) { t, v ->
                    Keyframe(t, Vector3f(v).div(16f)) // pixels -> blocks
                }
                val scales = track(bone.get("scale"), name) { t, v -> Keyframe(t, Vector3f(v)) }

                // Each track is sorted ascending, so its last key is its max time; take the max
                // across all three (concatenating and reading one `last` would miss the others).
                for (track in listOf(rotations, positions, scales)) {
                    track.lastOrNull()?.let { maxTime = maxOf(maxTime, it.timeSeconds) }
                }
                channels[boneIdx] = BoneChannel(positions, rotations, scales)
            }
            name to Animation(name, maxTime, channels)
        }
    }

    // One keyed track: either a static value (constant key at t=0) or a "time": value map.
    private inline fun <T> track(el: JsonElement?, anim: String, make: (Float, Vector3f) -> Keyframe<T>): List<Keyframe<T>> {
        el ?: return emptyList()
        vector(el)?.let { return listOf(make(0f, it)) }
        if (!el.isJsonObject) return emptyList()
        return el.asJsonObject.entrySet().mapNotNull { (time, value) ->
            val t = time.toFloatOrNull() ?: return@mapNotNull null
            vector(value)?.let { make(t, it) } ?: run {
                LOG.warn("BlackVertex: animation '{}' has a non-numeric keyframe at t={} (Molang?), skipped", anim, time)
                null
            }
        }.sortedBy { it.timeSeconds }
    }

    // Accepts [x,y,z], a single number, {"vector":[...]} and {"post":[...]} (pre/post
    // discontinuities collapse to post; GeckoLib easings degrade to linear).
    private fun vector(el: JsonElement): Vector3f? = when {
        el.isJsonArray -> el.asJsonArray.takeIf { it.size() >= 3 && it.all { e -> isNumber(e) } }
            ?.let { Vector3f(it[0].asFloat, it[1].asFloat, it[2].asFloat) }
        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asFloat.let { Vector3f(it, it, it) }
        el.isJsonObject -> (el.asJsonObject.get("post") ?: el.asJsonObject.get("vector"))?.let { vector(it) }
        else -> null
    }

    private fun isNumber(e: JsonElement) = e.isJsonPrimitive && e.asJsonPrimitive.isNumber
}
