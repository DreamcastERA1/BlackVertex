package org.blackaddons.blackvertex.api

import org.blackaddons.blackvertex.api.model.Model

/**
 * Contract for turning a source file into a [Model]. The renderer and skinning
 * backends only ever see [Model], so any format that can produce one plugs in here
 * without touching rendering: `.bobj` today, `.obj` or a cuboid generator later.
 *
 * An unskinned format (e.g. plain `.obj`) still yields a valid [Model] — a single
 * root bone with all vertex weight on it, which the skinning path treats as identity.
 */
interface ModelLoader {
    /** Lowercase, dot-less extensions this loader claims, e.g. {"bobj"}. */
    val extensions: Set<String>

    fun load(text: String): Model
}
