package org.blackaddons.blackvertex.render.gpu

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.*
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.FeatureRenderer
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase
import net.minecraft.client.renderer.feature.submit.SubmitNode
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.backend.gpu.GpuMesh
import org.blackaddons.blackvertex.backend.gpu.SkinnedFormat
import org.blackaddons.blackvertex.render.gpu.GpuCosmetics.PIPELINE
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.*

private typealias Tex = List<PreparedRenderType.Texture>

@OptIn(InternalBlackVertexApi::class)
internal object GpuCosmetics : SkinnedGpuBackend<Tex>() {

    private val PALETTE_LAYOUT: BindGroupLayout =
        BindGroupLayout.builder().withUniform("BonePalette", UniformType.UNIFORM_BUFFER).build()

    private fun skinnedPipelineBuilder(name: String): RenderPipeline.Builder =
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
            .withLocation(id("pipeline/$name"))
            .withVertexShader(id("core/entity_skinned"))
            .withFragmentShader(id("core/entity_skinned"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withShaderDefine("MAX_BONES", GpuMesh.MAX_BONES)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withBindGroupLayout(PALETTE_LAYOUT)
            .withVertexBinding(0, SkinnedFormat.FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)

    /** Skinning pipeline: entity-style state, our vertex format, TRIANGLES, palette UBO. */
    val PIPELINE: RenderPipeline by lazy {
        RenderPipelines.register(
            skinnedPipelineBuilder("entity_skinned")
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                .build()
        )
    }

    /** Like [PIPELINE] with vanilla's TRANSLUCENT blend (mirrors entity_translucent). */
    val PIPELINE_TRANSLUCENT: RenderPipeline by lazy {
        RenderPipelines.register(
            skinnedPipelineBuilder("entity_skinned_translucent")
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .build()
        )
    }

    /** Fullbright glow layer (mirrors entity_translucent_emissive: blend, no lightmap, no depth write). */
    val PIPELINE_EMISSIVE: RenderPipeline by lazy {
        RenderPipelines.register(
            skinnedPipelineBuilder("entity_skinned_emissive")
                .withShaderDefine("EMISSIVE")
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .build()
        )
    }

    override fun pipelineFor(mode: DrawMode): RenderPipeline = when (mode) {
        DrawMode.CUTOUT -> PIPELINE
        DrawMode.TRANSLUCENT -> PIPELINE_TRANSLUCENT
        DrawMode.EMISSIVE -> PIPELINE_EMISSIVE
    }

    override fun touchPipelines() {
        PIPELINE; PIPELINE_TRANSLUCENT; PIPELINE_EMISSIVE
    }

    override fun installInjection() {
        FeatureRendererRegistry.register(TYPE) { Renderer() }
    }

    val TYPE: FeatureRendererType<Submit> = FeatureRendererType.create("BlackVertexGpu")

    // Fabric hands back the built-in phases as raw FeatureRenderPhase<*>; casting to our node type is
    // safe because a phase is only a routing key and the sole thing we ever submit into it is a Submit.
    @Suppress("UNCHECKED_CAST")
    private val PHASE_SOLID = SubmitRenderPhase<Submit> { collection ->
        collection.solid as FeatureRenderPhase<Submit>
    }

    // Blended draws must run in the translucent stage or they composite wrongly behind water/glass
    // (the CPU path gets this for free from vanilla's render type sorting).
    @Suppress("UNCHECKED_CAST")
    private val PHASE_TRANSLUCENT = SubmitRenderPhase<Submit> { collection ->
        collection.translucentCustomGeometry as FeatureRenderPhase<Submit>
    }

    override fun submit(
        collector: SubmitNodeCollector,
        mesh: GpuMeshHandle,
        palette: PosePalette,
        pose: Matrix4f,
        texture: Identifier,
        light: Int,
        overlay: Int,
        mode: DrawMode,
        color: Int,
    ) {
        val phase = if (mode == DrawMode.CUTOUT) PHASE_SOLID else PHASE_TRANSLUCENT
        collector.submitCustom(phase, Submit(mesh as GpuMesh, palette, snapshotPose(pose), texture, light, overlay, mode, color))
    }

    class Submit(
        mesh: GpuMesh,
        palette: PosePalette,
        pose: Matrix4f,
        texture: Identifier,
        light: Int,
        overlay: Int,
        mode: DrawMode,
        color: Int,
    ) : Draw(mesh, palette, pose, texture, light, overlay, mode, color), SubmitNode {
        override fun featureType(): FeatureRendererType<Submit> = TYPE
    }

    override fun writeTransform(color: Vector4f): GpuBufferSlice =
        RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), color)

    override fun resolveTextures(setup: RenderSetup): Tex {
        val mc = Minecraft.getInstance()
        return setup.prepareTextures(
            mc.textureManager,
            RenderSystem.getSamplerCache(),
            mc.gameRenderer.overlayTexture().textureView,
            mc.gameRenderer.lightmap(),
        )
    }

    override fun bindTextures(pass: RenderPass, textures: Tex) {
        for (t in textures) pass.bindTexture(t.name, t.textureView, t.sampler)
    }

    override fun openPass(): RenderPass {
        // Same target resolution as vanilla PreparedRenderType.drawFromBuffer.
        val target = OutputTarget.MAIN_TARGET.renderTarget
        val color = RenderSystem.outputColorTextureOverride ?: checkNotNull(target.colorTextureView)
        val depth = if (target.useDepth) RenderSystem.outputDepthTextureOverride ?: target.depthTextureView else null
        return RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass({ "blackvertex gpu cosmetics" }, color, Optional.empty(), depth, OptionalDouble.empty())
    }

    override fun drawMesh(pass: RenderPass, mesh: GpuMesh) {
        pass.setVertexBuffer(0, mesh.vertexBuffer.slice())
        pass.setIndexBuffer(mesh.indexBuffer, IndexType.INT)
        pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0)
    }

    private class Renderer : FeatureRenderer<Submit> {

        // One ordered draw list per prepared group; a failed prepare adds an empty one to keep indices.
        private val groups = ArrayList<List<PreparedDraw<Tex>>>()

        override fun prepareGroup(context: FeatureFrameContext, submits: List<Submit>, strictlyOrdered: Boolean) {
            groups.add(
                try {
                    orderForSinglePass(prepare(submits))
                } catch (t: Throwable) {
                    disable("palette upload/prepare failed", t)
                    emptyList()
                }
            )
        }

        override fun executeGroup(context: FeatureFrameContext, groupIndex: Int, submits: List<Submit>, strictlyOrdered: Boolean) {
            val group = groups.getOrNull(groupIndex) ?: return
            try {
                drawGroup(group)
            } catch (t: Throwable) {
                disable("draw execution failed", t)
            }
        }

        override fun finishExecute(context: FeatureFrameContext) {
            groups.clear()
            retireFrame()
        }

        override fun close() {
            closeBuffers()
        }
    }
}
