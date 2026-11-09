package org.blackaddons.blackvertex.backend.gpu

import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement

// 26.1.2 skinned vertex format, built from VertexFormatElement (this version has no GpuFormat).
//
// VertexFormat finds each element's byte offset by id through the GLOBAL registry: byId(id) is matched against the
// format's elements by RECORD value equality, and an id whose registered element does not equal ours gives offset -1,
// which is GL_INVALID_VALUE. So custom elements MUST be registered at ids that are currently free in the global
// registry. Position and UV0 reuse vanilla elements, because they are equal (FLOAT x3 and FLOAT x2). Normal is a custom
// FLOAT x3 (vanilla NORMAL is BYTE x3), and the bone indices and weights are normalized bytes that the vsh decodes. The
// shared GpuMesh packs the matching bytes.
internal object SkinnedFormat {
    private val NORMAL = registerFree(VertexFormatElement.Type.FLOAT, false, 3)
    private val BONE_INDICES = registerFree(VertexFormatElement.Type.UBYTE, true, 4)
    private val BONE_WEIGHTS = registerFree(VertexFormatElement.Type.UBYTE, true, 4)

    val FORMAT: VertexFormat = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("UV0", VertexFormatElement.UV0)
        .add("Normal", NORMAL)
        .add("BoneIndices", BONE_INDICES)
        .add("BoneWeights", BONE_WEIGHTS)
        .build()

    private fun registerFree(type: VertexFormatElement.Type, normalized: Boolean, count: Int): VertexFormatElement {
        for (id in 0 until VertexFormatElement.MAX_COUNT) {
            if (VertexFormatElement.byId(id) == null) {
                return VertexFormatElement.register(id, 0, type, normalized, count)
            }
        }
        error("No free vertex format element ids")
    }
}
