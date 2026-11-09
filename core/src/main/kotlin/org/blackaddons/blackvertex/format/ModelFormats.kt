package org.blackaddons.blackvertex.format

import org.blackaddons.blackvertex.api.ModelLoader
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.format.ModelFormats.register
import org.blackaddons.blackvertex.format.bobj.BobjLoader
import org.blackaddons.blackvertex.format.geo.GeoJsonLoader

/**
 * A registry that hands a source file to the right [ModelLoader] by extension.
 *
 * Built-in formats are registered on first use. Add more with [register] (an `.obj` loader or a cuboid model generator,
 * for example) without changing any rendering code.
 */
object ModelFormats {

    private val loaders = mutableListOf(BobjLoader, GeoJsonLoader)

    fun register(loader: ModelLoader) {
        loaders.add(0, loader) // last registered wins on extension conflicts
    }

    fun loaderFor(extension: String): ModelLoader? {
        val ext = extension.removePrefix(".").lowercase()
        return loaders.firstOrNull { ext in it.extensions }
    }

    /**
     * Loads [text] with the loader that handles [fileName]'s extension. Throws [IllegalArgumentException] on an unknown
     * extension or malformed input, so wrap it when loading untrusted or downloaded assets.
     */
    fun load(fileName: String, text: String): Model {
        val ext = fileName.substringAfterLast('.', "")
        val loader = loaderFor(ext)
            ?: throw IllegalArgumentException("No ModelLoader registered for extension '.$ext' ($fileName)")
        return loader.load(text)
    }
}
