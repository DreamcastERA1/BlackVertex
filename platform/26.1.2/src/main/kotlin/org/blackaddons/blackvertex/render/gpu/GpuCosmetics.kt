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
import java.util.OptionalDouble
import java.util.OptionalInt

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
                // Same as vanilla ENTITY_TRANSLUCENT_EMISSIVE. 26.1.2 uses standard Z depth, so the compare is
                // LESS_THAN_OR_EQUAL. 26.2 switched to reversed Z with GREATER_THAN_OR_EQUAL, so this constant differs
                // per version and must NOT be copied between backends.
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

    // 26.1.2 has no feature renderer registry; FeatureRenderDispatcherMixin drives the draws.
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
        // collector is unused on 26.1.2, because the mixin injects the draw instead of using vanilla's submit system.
        if (!active) return
        queue.add(Draw(mesh as GpuMesh, palette, snapshotPose(pose), texture, light, overlay, mode, color))
    }

    // On 26.1.2, writeTransform takes (modelView, color, modelOffset, texMat).
    override fun writeTransform(color: Vector4f): GpuBufferSlice =
        RenderSystem.getDynamicUniforms()
            .writeTransform(Matrix4f(RenderSystem.getModelViewMatrix()), color, ZERO, IDENTITY)

    // getTextures() resolves Sampler0 plus the lightmap and overlay for the current frame. It is the 26.1.2 counterpart
    // of prepareTextures() on 26.2, and a good place to check lighting in game.
    override fun resolveTextures(setup: RenderSetup): Tex = setup.textures

    override fun bindTextures(pass: RenderPass, textures: Tex) {
        for ((name, tas) in textures) pass.bindTexture(name, tas.textureView(), tas.sampler())
    }

    override fun setPipeline(pass: RenderPass, pipeline: RenderPipeline) = pass.setPipeline(pipeline)

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

    // Draws already flushed this frame, and the prepared tail of this scope.
    //
    // A frame runs several feature render scopes (the world pass, then one GUI portrait per PiP, then the first-person
    // hand), and each ends in the renderSolid/renderBlended pair below. The queue is only emptied at endFrame, so a
    // scope must draw ONLY what was submitted since the previous scope ended, or it paints the whole world's cosmetics
    // into itself. That is the whole invariant, and no scope needs to know which one it is. (It used to guess by
    // whether it was inside renderAllFeatures, and got it wrong: GUI PiPs go through renderAllFeatures too, so every
    // portrait drew nothing.)
    private var flushed = 0
    private var scope: List<PreparedDraw<Tex>> = emptyList()

    /**
     * Called by the mixin at the tail of FeatureRenderDispatcher.renderSolidFeatures. Prepares this scope and draws the
     * cutout draws.
     */
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

    /**
     * Called by the mixin at the tail of FeatureRenderDispatcher.renderTranslucentFeatures. Draws the blended draws,
     * from far to near.
     */
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

    /** Called by the mixin at FeatureRenderDispatcher.endFrame. Clears the queue and retires buffers and meshes. */
    fun endFrame() {
        queue.clear()
        flushed = 0
        scope = emptyList()
        retireFrame()
    }

    /** Called by the mixin at FeatureRenderDispatcher.close. Frees every buffer and mesh at shutdown. */
    fun close() = closeBuffers()

    private val IDENTITY = Matrix4f()
    private val ZERO = Vector3f()
}
