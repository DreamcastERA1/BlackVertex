package org.blackaddons.blackvertex.api.model

/**
 * An immutable skeletal mesh: geometry, bones and animation clips. Load one through
 * [org.blackaddons.blackvertex.format.ModelFormats]. It does not depend on the renderer.
 */
class Model(
    val meshes: List<Mesh>,
    val skeleton: Skeleton,
    /** Animation clips by name, for example "idle" or "idlealt". */
    val animations: Map<String, Animation>,
) {
    val hasSkeleton: Boolean get() = skeleton.boneCount > 0

    /**
     * A copy with the [extra] clips merged in, replacing clips with the same name. For example, clips from a GeckoLib
     * `.animation.json`.
     */
    fun withAnimations(extra: Map<String, Animation>): Model = Model(meshes, skeleton, animations + extra)

    override fun toString(): String =
        "Model(meshes=${meshes.size}, bones=${skeleton.boneCount}, " +
            "anims=${animations.keys}, verts=${meshes.sumOf { it.vertices.size }})"
}
