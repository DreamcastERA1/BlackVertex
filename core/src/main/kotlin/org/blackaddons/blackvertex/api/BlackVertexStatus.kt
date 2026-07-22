package org.blackaddons.blackvertex.api

import org.blackaddons.blackvertex.api.BlackVertexStatus.backend


/** Which path is skinning and drawing cosmetics right now. */
enum class RenderBackend {
    /** GPU skinning: static buffers + palette UBO. The default. */
    GPU,

    /** CPU path forced via `-Dblackvertex.backend=cpu` (debugging/comparison). */
    CPU_FORCED,

    /**
     * GPU path failed at runtime (pipeline compile or draw error) and the library fell
     * back to CPU skinning. Details are in [BlackVertexStatus.fallbackReason] and the log.
     */
    CPU_FALLBACK,
}

/**
 * Read-only runtime status for the consuming mod; meaningful after client init has run.
 * BlackVertex itself only logs a warning on fallback — whether/how to tell the player is the
 * consumer's call ([RenderBackend.CPU_FALLBACK] means a real perf drop on crowded lobbies).
 */
object BlackVertexStatus {

    @Volatile
    var backend: RenderBackend = RenderBackend.GPU

    /** Human-readable reason when [backend] is [RenderBackend.CPU_FALLBACK], else null. */
    @Volatile
    var fallbackReason: String? = null
}
