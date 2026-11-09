package org.blackaddons.blackvertex.api

import org.blackaddons.blackvertex.api.model.Model

/**
 * Turns a source file into a [Model]. The renderer and the skinning backends only ever see [Model], so any format that
 * can produce one plugs in here without touching rendering: `.bobj` today, `.obj` or a cuboid generator later.
 *
 * An unskinned format (like plain `.obj`) still produces a valid [Model]: a single root bone with all vertex weight on
 * it, which the skinning path treats as identity.
 */
interface ModelLoader {
    /** The lowercase extensions, without the dot, that this loader handles, for example {"bobj"}. */
    val extensions: Set<String>

    fun load(text: String): Model
}
