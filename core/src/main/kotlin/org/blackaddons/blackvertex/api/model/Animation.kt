package org.blackaddons.blackvertex.api.model

import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * A named animation clip. Each animated bone has a [BoneChannel]; bones without a
 * channel stay in their bind pose.
 *
 * Keyed values are *basis deltas* relative to the bone's rest pose (Blender pose
 * semantics), already baked from the source f-curves: Euler rotation is converted to
 * quaternions and per-component curves are merged onto shared keyframe times. Times
 * are in seconds (source frames divided by the scene FPS).
 */
class Animation(
    val name: String,
    val durationSeconds: Float,
    /** Keyed by bone index. */
    val channels: Map<Int, BoneChannel>,
)

/**
 * Keyframe tracks for one bone. Tracks are independent; an empty track means that
 * component stays at its identity default (translation 0, rotation identity, scale 1).
 */
class BoneChannel(
    val translations: List<Keyframe<Vector3f>>,
    val rotations: List<Keyframe<Quaternionf>>,
    val scales: List<Keyframe<Vector3f>>,
)

class Keyframe<T>(val timeSeconds: Float, val value: T)
