package org.blackaddons.blackvertex.api.model

import org.joml.Matrix4f

/** Bone hierarchy. Bones are stored in topological order: a parent always precedes its children. */
class Skeleton(val bones: List<Bone>) {
    val boneCount: Int get() = bones.size

    private val indexByName: Map<String, Int> =
        bones.withIndex().associate { (i, b) -> b.name to i }

    /** Bone index by name, or -1 if absent. */
    fun indexOf(name: String): Int = indexByName[name] ?: -1

    companion object {
        /** A one-bone skeleton with an identity root — for unskinned/static models. */
        fun singleRoot(name: String = "root"): Skeleton =
            Skeleton(listOf(Bone(name, -1, Matrix4f(), Matrix4f())))

        /**
         * Build from bones given by their global rest matrix, in parent-before-child order.
         * Derives each [Bone.bindLocal] (parentGlobal⁻¹·global) and [Bone.inverseBind] (global⁻¹) —
         * the bind convention every loader must share, or the animated mesh explodes.
         */
        fun fromGlobalBind(bones: List<GlobalBone>): Skeleton {
            val indexByName = bones.withIndex().associate { (i, b) -> b.name to i }
            val globalByName = bones.associate { it.name to it.global }
            val built = bones.mapIndexed { i, b ->
                val parentIdx = b.parent?.let {
                    indexByName[it] ?: throw IllegalArgumentException("bone '${b.name}': unknown parent '$it'")
                } ?: -1
                require(parentIdx < i) { "bone '${b.name}' appears before its parent '${b.parent}'" }
                val parentGlobal = b.parent?.let { globalByName.getValue(it) }
                val bindLocal = if (parentGlobal != null) Matrix4f(parentGlobal).invert().mul(b.global) else Matrix4f(b.global)
                Bone(b.name, parentIdx, bindLocal, Matrix4f(b.global).invert())
            }
            return Skeleton(built)
        }
    }
}

/** A bone described by its global rest matrix, the input to [Skeleton.fromGlobalBind]. */
class GlobalBone(val name: String, val parent: String?, val global: Matrix4f)

/**
 * A single bone.
 *
 * The .bobj stores each bone's global rest matrix (armature space). From it we derive:
 *  - [bindLocal]  : rest transform relative to the parent. Animation applies a *basis*
 *    delta on top of this (`animatedLocal = bindLocal * basis`), matching Blender pose
 *    semantics — keyed rotation/scale are deltas from rest, not absolute local values.
 *  - [inverseBind]: inverse of the global rest matrix. Moves a vertex from model space
 *    into bone space so the animated global transform can place it. Missing this =
 *    exploding mesh.
 */
class Bone(
    val name: String,
    /** Index into [Skeleton.bones], or -1 for a root bone. */
    val parent: Int,
    val bindLocal: Matrix4f,
    val inverseBind: Matrix4f,
)
