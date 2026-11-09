package org.blackaddons.blackvertex.format.bobj

import org.blackaddons.blackvertex.api.ModelLoader
import org.blackaddons.blackvertex.api.model.Model

// The ModelLoader for the Blockbuster OBJ format, built on BobjParser.
internal object BobjLoader : ModelLoader {
    override val extensions: Set<String> = setOf("bobj")
    override fun load(text: String): Model = BobjParser.parse(text)
}
