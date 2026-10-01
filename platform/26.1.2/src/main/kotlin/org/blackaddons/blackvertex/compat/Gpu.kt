/**
 * The render-API classes shared code names, resolved for this Minecraft version. 26.3 moved them
 * from `com.mojang.blaze3d` to `com.mojang.renderpearl`; shared sources import these aliases so one
 * import compiles against every platform. The 26.3 sibling points the same names at the new package.
 */
package org.blackaddons.blackvertex.compat

typealias GpuBuffer = com.mojang.blaze3d.buffers.GpuBuffer
typealias GpuBufferSlice = com.mojang.blaze3d.buffers.GpuBufferSlice
typealias RenderPipeline = com.mojang.blaze3d.pipeline.RenderPipeline
typealias RenderPass = com.mojang.blaze3d.systems.RenderPass
