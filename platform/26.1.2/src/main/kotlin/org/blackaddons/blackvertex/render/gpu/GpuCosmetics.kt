package org.blackaddons.blackvertex.render.gpu

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.backend.gpu.GpuMesh
import org.blackaddons.blackvertex.backend.gpu.SkinnedFormat
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.*

private typealias Tex = Map<String, RenderSetup.TextureAndSampler>

@OptIn(InternalBlackVertexApi::class)
internal object GpuCosmetics : SkinnedGpuBackend<Tex>() {

    private fun skinnedPipelineBuilder(name: String): RenderPipeline.Builder =
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
            .withLocation(id("pipeline/$name"))
            .withVertexShader(id("core/entity_skinned"))
            .withFragmentShader(id("core/entity_skinned"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withShaderDefine("MAX_BONES", GpuMesh.MAX_BONES)
            .withSampler("Sampler0") // texture (fsh)
            .withSampler("Sampler1") // overlay (vsh)
            .withUniform("BonePalette", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(SkinnedFormat.FORMAT, VertexFormat.Mode.TRIANGLES)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)

    val PIPELINE: RenderPipeline by lazy {
        RenderPipelines.register(skinnedPipelineBuilder("entity_skinned").withSampler("Sampler2").build())
    }

    val PIPELINE_TRANSLUCENT: RenderPipeline by lazy {
        RenderPipelines.register(
            skinnedPipelineBuilder("entity_skinned_translucent")
                .withSampler("Sampler2")
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .build()
        )
    }

    val PIPELINE_EMISSIVE: RenderPipeline by lazy {
        RenderPipelines.register(
            skinnedPipelineBuilder("entity_skinned_emissive")
                .withShaderDefine("EMISSIVE")
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                // Mirrors vanilla ENTITY_TRANSLUCENT_EMISSIVE. 26.1.2 uses standard-Z depth, so the
                // compare is LESS_THAN_OR_EQUAL (26.2 flipped to reversed-Z / GREATER_THAN_OR_EQUAL —
                // a per-version constant, NOT copyable across the two backends).
                .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
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

    // No feature-renderer registry on 26.1.2 — FeatureRenderDispatcherMixin drives the draws.
    override fun installInjection() {}

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
        // collector is unused on 26.1.2: the mixin injects the draw, not the vanilla submit system.
        if (!active) return
        queue.add(Draw(mesh as GpuMesh, palette, snapshotPose(pose), texture, light, overlay, mode, color))
    }

    // 26.1.2 writeTransform takes (modelView, color, modelOffset, texMat).
    override fun writeTransform(color: Vector4f): GpuBufferSlice =
        RenderSystem.getDynamicUniforms()
            .writeTransform(Matrix4f(RenderSystem.getModelViewMatrix()), color, ZERO, IDENTITY)

    // getTextures() resolves Sampler0 + (lightmap/overlay) for the current frame — the 26.1.2
    // counterpart of 26.2's prepareTextures(); a prime in-game verify point for lighting.
    override fun resolveTextures(setup: RenderSetup): Tex = setup.textures

    override fun bindTextures(pass: RenderPass, textures: Tex) {
        for ((name, tas) in textures) pass.bindTexture(name, tas.textureView(), tas.sampler())
    }

    override fun openPass(): RenderPass {
        val target = OutputTarget.MAIN_TARGET.renderTarget
        val color = RenderSystem.outputColorTextureOverride ?: checkNotNull(target.colorTextureView)
        val depth = if (target.useDepth) RenderSystem.outputDepthTextureOverride ?: target.depthTextureView else null
        return RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass({ "blackvertex gpu cosmetics" }, color, OptionalInt.empty(), depth, OptionalDouble.empty())
    }

    override fun drawMesh(pass: RenderPass, mesh: GpuMesh) {
        pass.setVertexBuffer(0, mesh.vertexBuffer)
        pass.setIndexBuffer(mesh.indexBuffer, VertexFormat.IndexType.INT)
        pass.drawIndexed(0, 0, mesh.indexCount, 1)
    }

    private val queue = ArrayList<Draw>()

    // Draws already flushed this frame, and this scope's prepared tail.
    //
    // A frame runs several feature-render scopes — the world pass, then a GUI portrait per PiP, the
    // first-person hand — and each ends in the renderSolid/renderBlended pair below. The queue is only
    // emptied at endFrame, so a scope must draw ONLY what was submitted since the previous one ended,
    // or it repaints the whole world's cosmetics into itself. That is the entire invariant: no scope
    // needs to know which one it is. (It used to guess, by whether it sat inside renderAllFeatures,
    // and got it wrong — GUI PiPs go through renderAllFeatures too, so every portrait drew nothing.)
    private var flushed = 0
    private var scope: List<PreparedDraw<Tex>> = emptyList()

    /** Mixin: tail of FeatureRenderDispatcher.renderSolidFeatures — prepares this scope, draws cutout. */
    fun renderSolid() {
        if (!active) { queue.clear(); flushed = 0; return }
        try {
            scope = prepare(queue.subList(flushed, queue.size))
            flushed = queue.size
            drawGroup(scope.filter { it.mode == DrawMode.CUTOUT })
        } catch (t: Throwable) {
            disable("draw (solid) failed", t)
        }
    }

    /** Mixin: tail of FeatureRenderDispatcher.renderTranslucentFeatures — draws blended, far→near. */
    fun renderBlended() {
        if (!active || scope.isEmpty()) return
        try {
            drawGroup(scope.filter { it.mode != DrawMode.CUTOUT }.sortedByDescending { it.distSq })
        } catch (t: Throwable) {
            disable("draw (blended) failed", t)
        } finally {
            scope = emptyList()
        }
    }

    /** Mixin: FeatureRenderDispatcher.endFrame — clear the queue and retire buffers/meshes. */
    fun endFrame() {
        queue.clear()
        flushed = 0
        scope = emptyList()
        retireFrame()
    }

    private val IDENTITY = Matrix4f()
    private val ZERO = Vector3f()
}
