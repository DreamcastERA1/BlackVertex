package org.blackaddons.blackvertex.backend.gpu

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.vertex.VertexFormat

// 26.2 skinned vertex format: the WebGPU-style GpuFormat attribute API. Bone indices are a real
// integer (uint) attribute here. The shared GpuMesh packs the matching bytes; this only declares them.
internal object SkinnedFormat {
    val FORMAT: VertexFormat = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
        .addAttribute("BoneIndices", GpuFormat.RGBA8_UINT)
        .addAttribute("BoneWeights", GpuFormat.RGBA8_UNORM)
        .build()
}
