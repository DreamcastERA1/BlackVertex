@file:OptIn(InternalBlackVertexApi::class)

package org.blackaddons.blackvertex.render.gpu

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
import org.blackaddons.blackvertex.compat.GpuBuffer
import org.blackaddons.blackvertex.compat.GpuBufferSlice
import org.blackaddons.blackvertex.compat.RenderPass
import org.blackaddons.blackvertex.compat.RenderPipeline
import org.joml.Matrix4f
import org.joml.Vector4f
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** One queued skinned cosmetic draw, with everything [SkinnedGpuBackend.prepare] needs to pack it. */
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

/** A [Draw] resolved into GPU-ready slices and handles. [textures] is the platform's texture binding type. */
internal class PreparedDraw<T>(
    val mesh: GpuMesh,
    val paletteSlice: GpuBufferSlice,
    // The global camera transform plus this draw's ColorModulator, shared between draws with the same color.
    val dynamicTransforms: GpuBufferSlice,
    val textures: T,
    val mode: DrawMode,
    val distSq: Float, // camera-relative, for back-to-front ordering of blended draws
)

/**
 * The version-neutral core of the persistent buffer GPU skinning path, shared by all platforms. It owns the failure
 * latch, the mesh and render setup caches, palette UBO packing, the draw loop and buffer retirement. Each platform
 * subclass supplies the few primitives whose blaze3d signatures or frame injection differ: pipelines, texture
 * resolution, pass creation, mesh draw and the transform write.
 *
 * @param T the platform's resolved texture type ([prepareTextures] result or [getTextures] map).
 */
internal abstract class SkinnedGpuBackend<T> : GpuBackend {

    protected val log = LoggerFactory.getLogger("blackvertex")

    private val forcedBackend: String? = System.getProperty("blackvertex.backend")?.lowercase()

    // Latches to false on the first runtime failure, and the layer then sends every later frame through the CPU
    // skinner. BlackVertexStatus is the public view of this.
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
            { false } // Iris is absent or its API moved: never block the GPU path
        }
    }

    final override fun usable(): Boolean = active && !shaderPackInUse()

    // Switches to the CPU path for good, warns once and publishes the reason to BlackVertexStatus.
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


    /** A null entry means the model cannot use the GPU path (too many bones) and stays on the CPU path for good. */
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

    // Meshes whose Model no cosmetic uses anymore. Their buffers close a few frames later, because draws in flight may
    // still reference them. Render thread only.
    private val retiredMeshes = ArrayDeque<Pair<Int, GpuMesh>>()

    final override fun releaseUnused(stillUsed: Collection<Model>) {
        // Hop to the render thread, where the mesh cache and the retirement queue live.
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

    // Advanced once per frame. It timestamps the buffer and mesh retirement queues.
    protected var frame = 0
        private set

    // Reused across frames, so a steady number of cosmetics allocates nothing per frame. Pose snapshots and the palette
    // staging buffer are only read during prepare (the GPU buffer copies them synchronously, as the old code proved by
    // letting the staging buffer be collected while the GPU buffer stayed retired), so they can be reused the next
    // frame. The pose cursor resets at the end of the frame.
    private val posePool = ArrayList<Matrix4f>()
    private var poseCursor = 0
    private var staging: ByteBuffer? = null
    private val dynamicByColor = HashMap<Int, GpuBufferSlice>()
    private val perTexture = HashMap<Pair<Identifier, DrawMode>, T>()

    /** Copies [pose] into a pooled Matrix4f, because the caller changes its own matrix after submit returns. */
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

    /** Packs the palette UBO and resolves textures and transforms for [draws], in submission order. */
    internal fun prepare(draws: List<Draw>): List<PreparedDraw<T>> {
        if (draws.isEmpty() || !active) return emptyList()

        // One UBO for the whole group, with a 256-aligned slice per draw. The staging buffer is pooled.
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

        // Only the ColorModulator differs per cosmetic, so DynamicTransforms slices are shared between equal colors,
        // and textures between equal (texture, mode) pairs. Both maps are reused every frame.
        dynamicByColor.clear()
        perTexture.clear()
        return draws.mapIndexed { i, s ->
            val dynamic = dynamicByColor.getOrPut(s.color) { writeTransform(colorVec(s.color)) }
            val textures = perTexture.getOrPut(s.texture to s.mode) { resolveTextures(setupFor(s.texture, s.mode)) }
            // The pose translation is the camera-relative position of the attachment.
            val distSq = s.pose.m30() * s.pose.m30() + s.pose.m31() * s.pose.m31() + s.pose.m32() * s.pose.m32()
            val slice = buffer.slice(i.toLong() * PALETTE_STRIDE, PALETTE_BYTES.toLong())
            PreparedDraw(s.mesh, slice, dynamic, textures, s.mode, distSq)
        }
    }

    /**
     * Draws [draws] in the given order in one RenderPass, binding the pipeline and transform again only when they
     * change.
     */
    internal fun drawGroup(draws: List<PreparedDraw<T>>) {
        if (draws.isEmpty()) return
        openPass().use { pass -> drawInto(pass, draws) }
    }

    internal fun drawInto(pass: RenderPass, draws: List<PreparedDraw<T>>) {
        var pipeline: RenderPipeline? = null
        var boundDynamic: GpuBufferSlice? = null
        for (draw in draws) {
            val wanted = pipelineFor(draw.mode)
            if (wanted !== pipeline) {
                pipeline = wanted
                setPipeline(pass, wanted)
                // Bind again after a pipeline switch, because the bind group layouts differ per mode.
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

    /**
     * Advances the frame and closes buffers and meshes that were retired long enough ago that no draw still reads them.
     */
    internal fun retireFrame() {
        poseCursor = 0 // prepare used up the pose snapshots, so free the pool for the next frame
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

    /** Closes every live and retired palette buffer and mesh right away, when the backend shuts down. */
    internal fun closeBuffers() {
        for (b in liveBuffers) b.close()
        for ((_, b) in retired) b.close()
        liveBuffers.clear()
        retired.clear()
        for (mesh in meshes.values) mesh?.close()
        for ((_, mesh) in retiredMeshes) mesh.close()
        meshes.clear()
        retiredMeshes.clear()
    }

    // Cutout draws keep their submission order, and blended ones draw last, from far to near. Used by the backends that
    // draw everything in a single pass (26.2, 26.3); the mixin path filters per phase instead.
    protected fun orderForSinglePass(draws: List<PreparedDraw<T>>): List<PreparedDraw<T>> =
        draws.filter { it.mode == DrawMode.CUTOUT } +
            draws.filter { it.mode != DrawMode.CUTOUT }.sortedByDescending { it.distSq }

    protected fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("blackvertex", path)

    /** Forces the three lazy pipelines to register, before the pipelines are compiled. */
    protected abstract fun touchPipelines()

    /**
     * Installs the hook that puts the draw into the frame: a Fabric FeatureRenderer on 26.2 and 26.3, nothing on
     * 26.1.2, where a mixin does it.
     */
    protected abstract fun installInjection()

    protected abstract fun pipelineFor(mode: DrawMode): RenderPipeline

    /**
     * The DynamicTransforms slice: the global camera model-view plus the [color] ColorModulator (the argument count
     * differs per version).
     */
    protected abstract fun writeTransform(color: Vector4f): GpuBufferSlice

    /**
     * Resolves Sampler0, the lightmap and the overlay for the frame (prepareTextures on 26.2 and 26.3, getTextures on
     * 26.1.2).
     */
    protected abstract fun resolveTextures(setup: RenderSetup): T

    protected abstract fun bindTextures(pass: RenderPass, textures: T)

    protected abstract fun setPipeline(pass: RenderPass, pipeline: RenderPipeline)

    /**
     * Opens the cosmetics RenderPass on the main target (createRenderPass takes an Optional or an OptionalInt,
     * depending on the version).
     */
    protected open fun openPass(): RenderPass =
        throw UnsupportedOperationException("this platform draws into the pass Minecraft hands it")

    protected abstract fun drawMesh(pass: RenderPass, mesh: GpuMesh)

    protected companion object {
        const val POSE_OFFSET = GpuMesh.MAX_BONES * 64
        const val LIGHT_OFFSET = POSE_OFFSET + 64
        const val PALETTE_BYTES = LIGHT_OFFSET + 16
        const val PALETTE_STRIDE = (PALETTE_BYTES + 255) / 256 * 256
        const val BUFFER_RETIRE_FRAMES = 3

        private val IDENTITY = Matrix4f()

        // An ARGB int to a ColorModulator RGBA in 0..1.
        fun colorVec(argb: Int) = Vector4f(
            (argb ushr 16 and 0xFF) / 255f,
            (argb ushr 8 and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            (argb ushr 24 and 0xFF) / 255f,
        )
    }
}
