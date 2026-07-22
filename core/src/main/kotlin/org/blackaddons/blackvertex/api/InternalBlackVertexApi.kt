package org.blackaddons.blackvertex.api

/**
 * Marks skinning internals that are `public` only so sibling platform modules can use them across
 * the module boundary — they are not part of the supported API and may change or vanish without
 * notice. Consumers must not opt in; the marker exists to make an accidental dependency a compile
 * error, not to invite one.
 */
@RequiresOptIn(
    message = "BlackVertex internal API: not for consumer use; may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class InternalBlackVertexApi
