package org.blackaddons.blackvertex.api.model

/**
 * Immutable skeletal mesh: geometry + bones + animation clips. Load via
 * [org.blackaddons.blackvertex.format.ModelFormats]; renderer-agnostic.
 */
class Model(
    val meshes: List<Mesh>,
    val skeleton: Skeleton,
    /** Animation clips keyed by name (e.g. "idle", "idlealt"). */
    val animations: Map<String, Animation>,
) {
    val hasSkeleton: Boolean get() = skeleton.boneCount > 0

    /** Copy with [extra] clips merged in (same-name clips replaced) — e.g. GeckoLib `.animation.json`. */
    fun withAnimations(extra: Map<String, Animation>): Model = Model(meshes, skeleton, animations + extra)

    override fun toString(): String =
        "Model(meshes=${meshes.size}, bones=${skeleton.boneCount}, " +
            "anims=${animations.keys}, verts=${meshes.sumOf { it.vertices.size }})"
}
