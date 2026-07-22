@file:OptIn(InternalBlackVertexApi::class)

package org.blackaddons.blackvertex.render.gpu

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.BlackVertexStatus
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.RenderBackend
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.backend.gpu.GpuMesh
import org.joml.Matrix4f
import org.joml.Vector4f
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** One queued skinned cosmetic draw: everything [SkinnedGpuBackend.prepare] needs to pack it. */
internal open class Draw(
    val mesh: GpuMesh,
    val palette: PosePalette,
    val pose: Matrix4f,
    val texture: Identifier,
    val light: Int,
    val overlay: Int,
    val mode: DrawMode,
    val color: Int,
)

/** A [Draw] resolved into GPU-ready slices/handles; [textures] is the platform's texture binding type. */
internal class PreparedDraw<T>(
    val mesh: GpuMesh,
    val paletteSlice: GpuBufferSlice,
    // Global camera transform + this draw's ColorModulator; shared between draws of equal color.
    val dynamicTransforms: GpuBufferSlice,
    val textures: T,
    val mode: DrawMode,
    val distSq: Float, // camera-relative, for back-to-front ordering of blended draws
)

/**
 * Version-neutral core of the persistent-buffer GPU skinning path, shared by both platforms. It owns
 * the failure latch, mesh/render-setup caches, palette UBO packing, the draw loop and buffer
 * retirement; each platform subclass supplies the handful of primitives whose blaze3d signatures or
 * frame-injection differ (pipelines, texture resolution, pass creation, mesh draw, transform write).
 *
 * @param T the platform's resolved-texture type ([prepareTextures] result vs [getTextures] map).
 */
internal abstract class SkinnedGpuBackend<T> : GpuBackend {

    protected val log = LoggerFactory.getLogger("blackvertex")

    private val forcedBackend: String? = System.getProperty("blackvertex.backend")?.lowercase()

    // Latches false on the first runtime failure; the layer then routes every subsequent frame
    // through the CPU skinner. BlackVertexStatus is the public view of this.
    @Volatile
    var active: Boolean = forcedBackend != "cpu"
        private set

    private val shaderPackInUse: () -> Boolean = run {
        if (forcedBackend == "gpu") return@run { false }
        try {
            val api = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val instance = api.getMethod("getInstance").invoke(null)
            val method = api.getMethod("isShaderPackInUse");
            { method.invoke(instance) as Boolean }
        } catch (_: Throwable) {
            { false } // Iris absent (or its API moved) — never block the GPU path
        }
    }

    final override fun usable(): Boolean = active && !shaderPackInUse()

    // Flips to CPU permanently, warns once, publishes the reason to BlackVertexStatus.
    internal fun disable(reason: String, cause: Throwable? = null) {
        if (!active) return
        active = false
        BlackVertexStatus.backend = RenderBackend.CPU_FALLBACK
        BlackVertexStatus.fallbackReason = reason
        log.warn("BlackVertex: GPU cosmetics path disabled, falling back to CPU skinning ({})", reason, cause)
    }

    final override fun init() {
        if (!active) {
            BlackVertexStatus.backend = RenderBackend.CPU_FORCED
            log.info("BlackVertex: CPU skinning forced via -Dblackvertex.backend=cpu")
            return
        }
        try {
            touchPipelines() // register before the loading screen compiles pipelines
            installInjection()
            BlackVertexStatus.backend = RenderBackend.GPU
        } catch (t: Throwable) {
            disable("pipeline/feature registration failed", t)
        }
    }


    /** null marker = model not GPU-eligible (too many bones); falls back to CPU forever. */
    private val meshes = HashMap<Model, GpuMesh?>()
    private val setups = ConcurrentHashMap<Pair<Identifier, DrawMode>, RenderSetup>()

    final override fun meshFor(model: Model, textureKey: String): GpuMesh? =
        meshes.getOrPut(model) {
            try {
                GpuMesh.upload(model, textureKey)
            } catch (t: Throwable) {
                disable("mesh upload failed for '$textureKey'", t)
                null
            }
        }

    private fun setupFor(texture: Identifier, mode: DrawMode): RenderSetup =
        setups.computeIfAbsent(texture to mode) { (tex, m) ->
            val builder = RenderSetup.builder(pipelineFor(m)).withTexture("Sampler0", tex).useOverlay()
            if (m != DrawMode.EMISSIVE) builder.useLightmap() // emissive is fullbright
            builder.createRenderSetup()
        }

    // Meshes whose Model is no longer used by any cosmetic; buffers close a few frames later (in-flight
    // draws may still reference them). Render thread only.
    private val retiredMeshes = ArrayDeque<Pair<Int, GpuMesh>>()

    final override fun releaseUnused(stillUsed: Collection<Model>) {
        // Hop to the render thread: the mesh cache and retirement queue live there.
        Minecraft.getInstance().execute {
            val it = meshes.iterator()
            while (it.hasNext()) {
                val (model, mesh) = it.next()
                if (model !in stillUsed) {
                    it.remove()
                    if (mesh != null) retiredMeshes.addLast(frame to mesh)
                }
            }
        }
    }

    private val liveBuffers = ArrayList<GpuBuffer>()
    private val retired = ArrayDeque<Pair<Int, GpuBuffer>>()

    // Advanced once per frame; timestamps the buffer/mesh retirement queues.
    protected var frame = 0
        private set

    // Reused across frames so a steady cosmetic count allocates nothing per frame. Pose snapshots and
    // the palette staging buffer are read only during prepare (the GPU buffer copies them synchronously
    // — proven by the old code letting the staging buffer GC while the GPU buffer stayed retired), so
    // they are free to reuse next frame. The pose cursor resets at frame end.
    private val posePool = ArrayList<Matrix4f>()
    private var poseCursor = 0
    private var staging: ByteBuffer? = null
    private val dynamicByColor = HashMap<Int, GpuBufferSlice>()
    private val perTexture = HashMap<Pair<Identifier, DrawMode>, T>()

    /** Snapshot [pose] into a pooled Matrix4f — the caller mutates its own after submit returns. */
    protected fun snapshotPose(pose: Matrix4f): Matrix4f {
        val m = if (poseCursor < posePool.size) posePool[poseCursor] else Matrix4f().also { posePool.add(it) }
        poseCursor++
        return m.set(pose)
    }

    private fun stagingBuffer(bytes: Int): ByteBuffer {
        val cur = staging
        if (cur != null && cur.capacity() >= bytes) return cur.clear()
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { staging = it }
    }

    /** Packs the palette UBO and resolves textures/transforms for [draws] (submission order). */
    internal fun prepare(draws: List<Draw>): List<PreparedDraw<T>> {
        if (draws.isEmpty() || !active) return emptyList()

        // One UBO for the whole group; a 256-aligned slice per draw (staging buffer pooled).
        val data = stagingBuffer(draws.size * PALETTE_STRIDE)
        for ((i, s) in draws.withIndex()) {
            val base = i * PALETTE_STRIDE
            val matrices = s.palette.matrices
            for (b in 0 until GpuMesh.MAX_BONES) {
                (if (b < s.mesh.boneCount) matrices[b] else IDENTITY).get(base + b * 64, data)
            }
            s.pose.get(base + POSE_OFFSET, data)
            data.putInt(base + LIGHT_OFFSET, s.light and 0xFFFF)
            data.putInt(base + LIGHT_OFFSET + 4, (s.light shr 16) and 0xFFFF)
            data.putInt(base + LIGHT_OFFSET + 8, s.overlay and 0xFFFF)
            data.putInt(base + LIGHT_OFFSET + 12, (s.overlay shr 16) and 0xFFFF)
        }
        data.position(0).limit(draws.size * PALETTE_STRIDE)

        val buffer = RenderSystem.getDevice().createBuffer({ "blackvertex palettes" }, GpuBuffer.USAGE_UNIFORM, data)
        liveBuffers.add(buffer)

        // Only the ColorModulator differs per cosmetic, so DynamicTransforms slices are shared across
        // equal colors; textures are shared across equal (texture, mode). Both maps are reused per frame.
        dynamicByColor.clear()
        perTexture.clear()
        return draws.mapIndexed { i, s ->
            val dynamic = dynamicByColor.getOrPut(s.color) { writeTransform(colorVec(s.color)) }
            val textures = perTexture.getOrPut(s.texture to s.mode) { resolveTextures(setupFor(s.texture, s.mode)) }
            // Pose translation = camera-relative position of the attachment.
            val distSq = s.pose.m30() * s.pose.m30() + s.pose.m31() * s.pose.m31() + s.pose.m32() * s.pose.m32()
            val slice = buffer.slice(i.toLong() * PALETTE_STRIDE, PALETTE_BYTES.toLong())
            PreparedDraw(s.mesh, slice, dynamic, textures, s.mode, distSq)
        }
    }

    /** Draws [draws] in the given order in one RenderPass (pipeline/transform re-bound only on change). */
    internal fun drawGroup(draws: List<PreparedDraw<T>>) {
        if (draws.isEmpty()) return
        openPass().use { pass ->
            var pipeline: RenderPipeline? = null
            var boundDynamic: GpuBufferSlice? = null
            for (draw in draws) {
                val wanted = pipelineFor(draw.mode)
                if (wanted !== pipeline) {
                    pipeline = wanted
                    pass.setPipeline(wanted)
                    // Re-bind after a pipeline switch: bind-group layouts differ per mode.
                    RenderSystem.bindDefaultUniforms(pass)
                    boundDynamic = null
                }
                if (draw.dynamicTransforms !== boundDynamic) {
                    boundDynamic = draw.dynamicTransforms
                    pass.setUniform("DynamicTransforms", draw.dynamicTransforms)
                }
                pass.setUniform("BonePalette", draw.paletteSlice)
                bindTextures(pass, draw.textures)
                drawMesh(pass, draw.mesh)
            }
        }
    }

    /** Advance the frame and close buffers/meshes retired long enough ago that no draw still reads them. */
    internal fun retireFrame() {
        poseCursor = 0 // pose snapshots were consumed in prepare; free the pool for next frame
        frame++
        for (b in liveBuffers) retired.addLast(frame to b)
        liveBuffers.clear()
        while (retired.isNotEmpty() && frame - retired.first().first > BUFFER_RETIRE_FRAMES) {
            retired.removeFirst().second.close()
        }
        while (retiredMeshes.isNotEmpty() && frame - retiredMeshes.first().first > BUFFER_RETIRE_FRAMES) {
            retiredMeshes.removeFirst().second.close()
        }
    }

    /** Close every live/retired palette buffer immediately (backend teardown). */
    internal fun closeBuffers() {
        for (b in liveBuffers) b.close()
        for ((_, b) in retired) b.close()
        liveBuffers.clear()
        retired.clear()
    }

    // Cutout draws keep submission order; blended ones draw last, far -> near. Used where a backend
    // draws everything in a single pass (26.2); the mixin path filters per phase instead.
    protected fun orderForSinglePass(draws: List<PreparedDraw<T>>): List<PreparedDraw<T>> =
        draws.filter { it.mode == DrawMode.CUTOUT } +
            draws.filter { it.mode != DrawMode.CUTOUT }.sortedByDescending { it.distSq }

    protected fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("blackvertex", path)

    /** Force the three lazy pipelines to register (called before the pipeline-compile pass). */
    protected abstract fun touchPipelines()

    /** Install the frame-injection hook (Fabric FeatureRenderer on 26.2; nothing on 26.1.2's mixin). */
    protected abstract fun installInjection()

    protected abstract fun pipelineFor(mode: DrawMode): RenderPipeline

    /** DynamicTransforms slice = the global camera model-view + [color] ColorModulator (arg count differs). */
    protected abstract fun writeTransform(color: Vector4f): GpuBufferSlice

    /** Resolve Sampler0 + lightmap/overlay for the frame (26.2 prepareTextures vs 26.1.2 getTextures). */
    protected abstract fun resolveTextures(setup: RenderSetup): T

    protected abstract fun bindTextures(pass: RenderPass, textures: T)

    /** Open the cosmetics RenderPass on the main target (Optional vs OptionalInt in createRenderPass). */
    protected abstract fun openPass(): RenderPass

    protected abstract fun drawMesh(pass: RenderPass, mesh: GpuMesh)

    protected companion object {
        const val POSE_OFFSET = GpuMesh.MAX_BONES * 64
        const val LIGHT_OFFSET = POSE_OFFSET + 64
        const val PALETTE_BYTES = LIGHT_OFFSET + 16
        const val PALETTE_STRIDE = (PALETTE_BYTES + 255) / 256 * 256
        const val BUFFER_RETIRE_FRAMES = 3

        private val IDENTITY = Matrix4f()

        // ARGB int -> ColorModulator RGBA in 0..1.
        fun colorVec(argb: Int) = Vector4f(
            (argb ushr 16 and 0xFF) / 255f,
            (argb ushr 8 and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            (argb ushr 24 and 0xFF) / 255f,
        )
    }
}
