package org.blackaddons.blackvertex.backend.gpu

import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement

// 26.1.2 skinned vertex format: built from VertexFormatElement (no GpuFormat on this version).
// VertexFormat resolves each element's byte offset by id through the GLOBAL registry (byId(id) matched
// against the format's elements by RECORD value-equality — an id whose registered element doesn't
// value-match ours yields offset -1 == GL_INVALID_VALUE). So custom elements MUST be register()'d at
// ids free of vanilla's 0..6. Position/UV0 reuse vanilla elements (they value-match: FLOAT×3 / FLOAT×2);
// Normal is a custom FLOAT×3 (vanilla NORMAL is BYTE×3); bone indices/weights are normalized bytes
// recovered in the vsh. The shared GpuMesh packs the matching bytes.
internal object SkinnedFormat {
    private val NORMAL = VertexFormatElement.register(7, 0, VertexFormatElement.Type.FLOAT, false, 3)
    private val BONE_INDICES = VertexFormatElement.register(8, 0, VertexFormatElement.Type.UBYTE, true, 4)
    private val BONE_WEIGHTS = VertexFormatElement.register(9, 0, VertexFormatElement.Type.UBYTE, true, 4)

    val FORMAT: VertexFormat = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("UV0", VertexFormatElement.UV0)
        .add("Normal", NORMAL)
        .add("BoneIndices", BONE_INDICES)
        .add("BoneWeights", BONE_WEIGHTS)
        .build()
}
