/**
 * The render-API classes shared code names, resolved for this Minecraft version. 26.3 moved them
 * from `com.mojang.blaze3d` to `com.mojang.renderpearl`; shared sources import these aliases so one
 * import compiles against every platform. The 26.1.2 sibling points the same names at the old package.
 */
package org.blackaddons.blackvertex.compat

typealias GpuBuffer = com.mojang.renderpearl.api.buffers.GpuBuffer
typealias GpuBufferSlice = com.mojang.renderpearl.api.buffers.GpuBufferSlice
typealias RenderPipeline = com.mojang.renderpearl.api.pipeline.RenderPipeline
typealias RenderPass = com.mojang.renderpearl.api.commands.RenderPass
