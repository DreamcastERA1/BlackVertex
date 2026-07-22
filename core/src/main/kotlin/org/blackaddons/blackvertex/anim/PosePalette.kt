package org.blackaddons.blackvertex.anim

import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.model.Animation
import org.blackaddons.blackvertex.api.model.BoneChannel
import org.blackaddons.blackvertex.api.model.Keyframe
import org.blackaddons.blackvertex.api.model.Skeleton
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

// Computes the bone matrix palette (finalBone[i] = globalAnimated[i] * inverseBind[i])
// for a skeleton at a given animation time. Keyed values are basis deltas:
// animatedLocal = bindLocal * basis(loc, rot, scale), so a channel-less bone stays in
// rest pose. Not thread-safe; one instance per concurrent render job, zero-alloc per call.
@InternalBlackVertexApi
class PosePalette(private val skeleton: Skeleton) {

    val matrices: Array<Matrix4f> = Array(skeleton.boneCount) { Matrix4f() }

    // Global (model-space) animated transform per bone; parent-accumulated in one pass.
    private val global: Array<Matrix4f> = Array(skeleton.boneCount) { Matrix4f() }

    private val basis = Matrix4f()
    private val animatedLocal = Matrix4f()
    private val t = Vector3f()
    private val r = Quaternionf()
    private val s = Vector3f()

    /**
     * Fill [matrices] for [animation] sampled at [timeSeconds] (looping).
     * Pass a null animation for the bind pose.
     */
    fun update(animation: Animation?, timeSeconds: Float) {
        val time = if (animation != null && animation.durationSeconds > 0f)
            timeSeconds % animation.durationSeconds
        else 0f

        for (i in skeleton.bones.indices) {
            val bone = skeleton.bones[i]
            val channel = animation?.channels?.get(i)

            if (channel != null) {
                sampleBasis(channel, time, basis)
                animatedLocal.set(bone.bindLocal).mul(basis)
            } else {
                animatedLocal.set(bone.bindLocal)
            }

            if (bone.parent >= 0) {
                global[i].set(global[bone.parent]).mul(animatedLocal)
            } else {
                global[i].set(animatedLocal)
            }

            matrices[i].set(global[i]).mul(bone.inverseBind)
        }
    }

    private fun sampleBasis(channel: BoneChannel, time: Float, out: Matrix4f) {
        if (channel.translations.isEmpty()) t.set(0f) else sampleVec(channel.translations, time, t)
        if (channel.rotations.isEmpty()) r.identity() else sampleQuat(channel.rotations, time, r)
        if (channel.scales.isEmpty()) s.set(1f) else sampleVec(channel.scales, time, s)
        out.translationRotateScale(t, r, s)
    }

    private fun sampleVec(keys: List<Keyframe<Vector3f>>, time: Float, out: Vector3f): Vector3f {
        val a = locate(keys, time)
        val b = minOf(a + 1, keys.lastIndex) // single-key track: blend with itself
        return keys[a].value.lerp(keys[b].value, locAlpha, out)
    }

    private fun sampleQuat(keys: List<Keyframe<Quaternionf>>, time: Float, out: Quaternionf): Quaternionf {
        val a = locate(keys, time)
        val b = minOf(a + 1, keys.lastIndex)
        // JOML's slerp handles the dot<0 sign flip internally, avoiding the long path.
        return keys[a].value.slerp(keys[b].value, locAlpha, out)
    }

    // Result channel of locate(); a field instead of a Triple to keep sampling alloc-free.
    private var locAlpha = 0f

    // Lower keyframe index bracketing time (clamped so index+1 stays valid); locAlpha holds
    // the blend factor. Linear scan — per-bone key counts are small.
    private fun locate(keys: List<Keyframe<*>>, time: Float): Int {
        locAlpha = 0f
        if (keys.size == 1 || time <= keys.first().timeSeconds) return 0
        if (time >= keys.last().timeSeconds) {
            locAlpha = 1f
            return keys.lastIndex - 1
        }
        var i = 0
        while (i < keys.lastIndex && keys[i + 1].timeSeconds < time) i++
        val t0 = keys[i].timeSeconds
        val t1 = keys[i + 1].timeSeconds
        if (t1 > t0) locAlpha = (time - t0) / (t1 - t0)
        return i
    }
}
