package org.blackaddons.blackvertex.api

import org.blackaddons.blackvertex.api.BlackVertexStatus.backend


/** Which path is skinning and drawing cosmetics right now. */
enum class RenderBackend {
    /** GPU skinning with static buffers and a palette UBO. The default. */
    GPU,

    /** The CPU path, forced with `-Dblackvertex.backend=cpu` (for debugging or comparison). */
    CPU_FORCED,

    /**
     * The GPU path failed at runtime (a pipeline compile or draw error), and the library fell back to CPU skinning. The
     * details are in [BlackVertexStatus.fallbackReason] and the log.
     */
    CPU_FALLBACK,
}

/**
 * Read-only runtime status for the consuming mod, meaningful once client init has run. BlackVertex itself only logs a
 * warning on fallback. Whether and how to tell the player is up to the consumer ([RenderBackend.CPU_FALLBACK] means a
 * real performance drop in crowded lobbies).
 */
object BlackVertexStatus {

    @Volatile
    var backend: RenderBackend = RenderBackend.GPU

    /** A human-readable reason when [backend] is [RenderBackend.CPU_FALLBACK], otherwise null. */
    @Volatile
    var fallbackReason: String? = null
}
