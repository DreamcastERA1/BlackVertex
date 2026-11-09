package org.blackaddons.blackvertex.api.model

import org.joml.Matrix4f

/** The bone hierarchy. Bones are stored in topological order: a parent always comes before its children. */
class Skeleton(val bones: List<Bone>) {
    val boneCount: Int get() = bones.size

    private val indexByName: Map<String, Int> =
        bones.withIndex().associate { (i, b) -> b.name to i }

    /** The bone index for [name], or -1 if there is no such bone. */
    fun indexOf(name: String): Int = indexByName[name] ?: -1

    companion object {
        /** A one-bone skeleton with an identity root, for unskinned or static models. */
        fun singleRoot(name: String = "root"): Skeleton =
            Skeleton(listOf(Bone(name, -1, Matrix4f(), Matrix4f())))

        /**
         * Builds a skeleton from bones given by their global rest matrix, in parent-before-child order. It derives each
         * [Bone.bindLocal] (parentGlobal⁻¹·global) and [Bone.inverseBind] (global⁻¹). Every loader must share this bind
         * convention, or the animated mesh explodes.
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
 * The .bobj stores each bone's global rest matrix (armature space), and we derive two matrices from it:
 * - [bindLocal]: the rest transform relative to the parent. Animation applies a *basis* delta on top of it
 *   (`animatedLocal = bindLocal * basis`), as Blender poses do: keyed rotation and scale are deltas from the rest pose,
 *   not absolute local values.
 * - [inverseBind]: the inverse of the global rest matrix. It moves a vertex from model space into bone space, so the
 *   animated global transform can place it. Without it, the mesh explodes.
 */
class Bone(
    val name: String,
    /** The index into [Skeleton.bones], or -1 for a root bone. */
    val parent: Int,
    val bindLocal: Matrix4f,
    val inverseBind: Matrix4f,
)
