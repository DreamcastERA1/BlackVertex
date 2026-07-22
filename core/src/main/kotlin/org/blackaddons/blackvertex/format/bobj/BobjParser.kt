package org.blackaddons.blackvertex.format.bobj

import org.blackaddons.blackvertex.api.model.*
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import kotlin.math.roundToInt

/**
 * Parser for the Blockbuster OBJ (`.bobj`) format — an OBJ variant exported from
 * Blender by the McHorse Blockbuster add-on, extended with armature and animation.
 *
 * Grammar (whitespace-separated tokens, 1-based indices as in OBJ):
 * ```
 *  v  x y z                          vertex position
 *  vw <bone> <weight>                weight for the *preceding* v, by bone name
 *  vt u v                            texture coordinate (OBJ bottom-up v; flipped to top-down on parse)
 *  vn x y z                          normal
 *  f  v/vt/vn v/vt/vn ...            face (n-gons fan-triangulated)
 *  o  <name>                         start a submesh
 *  arm_bone <name> [parent] hx hy hz  m00..m33   bone: head + 4x4 rest matrix (row-major)
 *  an <name>                         start an animation clip
 *  ao <bone>                         select bone for following channels
 *  ag <prop> <component>             f-curve group: prop in {rotation,scale,location}
 *  kf <frame> <value> <interp> <hlx> <hly> <hrx> <hry>   keyframe (handles: left/right, absolute)
 * ```
 *
 * Interpolation: LINEAR and BEZIER (cubic, Blender F-curve handles; CONSTANT falls back to
 * linear). Curves containing a bezier segment are baked densely (one key per frame) so the
 * runtime sampler stays a plain lerp.
 *
 * Known simplifications (revisit if a model misbehaves):
 *  - Handle order is assumed `handle_left.xy handle_right.xy` (zeroed in known exports).
 *  - Euler rotation is baked XYZ (Blender default); bone names with spaces are unsupported.
 */
internal object BobjParser {

    const val DEFAULT_FPS: Float = 24f

    fun parse(text: String, fps: Float = DEFAULT_FPS): Model = parse(text.lineSequence(), fps)

    fun parse(lines: Sequence<String>, fps: Float = DEFAULT_FPS): Model {
        val positions = ArrayList<Vector3f>()
        val normals = ArrayList<Vector3f>()
        val uvs = ArrayList<Vector2f>()
        val weights = ArrayList<MutableList<Pair<String, Float>>>() // per position index

        val meshes = ArrayList<RawMesh>()
        var curMesh: RawMesh? = null
        var curVertex = -1

        val rawBones = LinkedHashMap<String, RawBone>() // insertion order = parent-before-child

        val rawAnims = LinkedHashMap<String, RawAnim>()
        var curAnim: RawAnim? = null
        var curAnimBone: String? = null
        var curProp: String? = null
        var curComp = -1

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val t = line.split(WS)
            when (t[0]) {
                "o" -> RawMesh(t.getOrElse(1) { "mesh" }).also { meshes.add(it); curMesh = it }

                "v" -> {
                    positions.add(vec3(t))
                    weights.add(ArrayList())
                    curVertex = positions.lastIndex
                }
                "vw" -> if (curVertex >= 0) weights[curVertex].add(t[1] to t[2].toFloat())
                // OBJ's v origin is the image's BOTTOM-left; Model (like MC) puts v=0 at the
                // TOP. Flip here so the renderer can hand UVs to the pipeline untouched.
                // Without this, cutout alpha punches mirrored holes into the mesh.
                "vt" -> uvs.add(Vector2f(t[1].toFloat(), 1f - t[2].toFloat()))
                "vn" -> normals.add(vec3(t))
                "f" -> parseFace(t, curMesh ?: RawMesh("mesh").also { meshes.add(it); curMesh = it })

                "arm_bone" -> parseBone(t)?.let { rawBones[it.name] = it }

                "an" -> RawAnim(t.getOrElse(1) { "anim" }).also { rawAnims[it.name] = it; curAnim = it }
                "ao" -> curAnimBone = t.getOrNull(1)
                "ag" -> { curProp = t.getOrNull(1); curComp = t.getOrNull(2)?.toIntOrNull() ?: -1 }
                "kf" -> {
                    val a = curAnim; val b = curAnimBone; val p = curProp
                    if (a != null && b != null && p != null && curComp >= 0) {
                        fun f(i: Int) = t.getOrNull(i)?.toFloatOrNull() ?: 0f
                        a.curve(b, p, curComp).add(
                            RawKey(
                                frame = t[1].toFloat().roundToInt(),
                                value = t[2].toFloat(),
                                bezier = t.getOrNull(3) == "BEZIER",
                                hlx = f(4), hly = f(5), hrx = f(6), hry = f(7),
                            )
                        )
                    }
                }
                // arm_name, arm_action, o_arm, usemtl, mtllib, s, ... : not needed for rendering
            }
        }

        val skeleton = buildSkeleton(rawBones)
        val builtMeshes = meshes.map { it.build(positions, normals, uvs, weights, skeleton) }
        val animations = rawAnims.mapValues { (_, a) -> a.bake(skeleton, fps) }
        return Model(builtMeshes, skeleton, animations)
    }

    private class RawMesh(val name: String) {
        // One IntArray{posIdx, uvIdx, normIdx} (0-based, -1 if absent) per corner, triangulated.
        val corners = ArrayList<IntArray>()

        fun build(
            positions: List<Vector3f>,
            normals: List<Vector3f>,
            uvs: List<Vector2f>,
            weights: List<List<Pair<String, Float>>>,
            skeleton: Skeleton,
        ): Mesh {
            val vertexIndex = HashMap<Long, Int>()
            val vertices = ArrayList<Vertex>()
            val indices = IntArray(corners.size)

            for (i in corners.indices) {
                val c = corners[i]
                // +1 so a missing (-1) uv/normal packs as 0 instead of corrupting the key.
                val key = ((c[0] + 1).toLong() shl 42) or ((c[1] + 1).toLong() shl 21) or (c[2] + 1).toLong()
                indices[i] = vertexIndex.getOrPut(key) {
                    vertices.add(makeVertex(c, positions, normals, uvs, weights, skeleton))
                    vertices.lastIndex
                }
            }
            return Mesh(name, vertices, indices, texture = null)
        }
    }

    private fun makeVertex(
        c: IntArray,
        positions: List<Vector3f>,
        normals: List<Vector3f>,
        uvs: List<Vector2f>,
        weights: List<List<Pair<String, Float>>>,
        skeleton: Skeleton,
    ): Vertex {
        val pos = Vector3f(positions[c[0]])
        val norm = if (c[2] >= 0) Vector3f(normals[c[2]]) else Vector3f(0f, 1f, 0f)
        val uv = if (c[1] >= 0) uvs[c[1]] else ZERO_UV

        // Resolve up to MAX_INFLUENCES weighted bones, normalized.
        val raw = weights.getOrElse(c[0]) { emptyList() }
            .mapNotNull { (name, w) ->
                val idx = skeleton.indexOf(name)
                if (idx >= 0 && w > 0f) idx to w else null
            }
            .sortedByDescending { it.second }
            .take(Vertex.MAX_INFLUENCES)

        val idx = IntArray(Vertex.MAX_INFLUENCES)
        val wgt = FloatArray(Vertex.MAX_INFLUENCES)
        if (raw.isEmpty()) {
            wgt[0] = 1f // fall back to the first bone (or a no-op if skeleton is empty)
        } else {
            val sum = raw.sumOf { it.second.toDouble() }.toFloat()
            raw.forEachIndexed { k, (b, w) -> idx[k] = b; wgt[k] = w / sum }
        }
        return Vertex(pos, norm, uv.x, uv.y, idx, wgt)
    }

    private fun parseFace(t: List<String>, mesh: RawMesh) {
        // Corners at t[1..]. Fan-triangulate: (0, i, i+1).
        val n = t.size - 1
        val parsed = Array(n) { i -> corner(t[i + 1]) }
        for (i in 1 until n - 1) {
            mesh.corners.add(parsed[0])
            mesh.corners.add(parsed[i])
            mesh.corners.add(parsed[i + 1])
        }
    }

    // "v/vt/vn" or "v//vn" or "v" -> [posIdx, uvIdx, normIdx] 0-based, -1 where missing.
    private fun corner(s: String): IntArray {
        val parts = s.split('/')
        fun idx(i: Int): Int = parts.getOrNull(i)?.toIntOrNull()?.let { it - 1 } ?: -1
        return intArrayOf(idx(0), idx(1), idx(2))
    }

    private class RawBone(val name: String, val parent: String?, val global: Matrix4f)

    private fun parseBone(t: List<String>): RawBone? {
        if (t.size < 3) return null
        val name = t[1]
        // t[2] is a parent name if it isn't numeric.
        val hasParent = t[2].toFloatOrNull() == null
        val parent = if (hasParent) t[2] else null
        val matStart = (if (hasParent) 3 else 2) + 3 // skip parent + 3 head floats
        if (t.size < matStart + 16) return null
        return RawBone(name, parent, matrixRowMajor(t, matStart))
    }

    private fun buildSkeleton(rawBones: Map<String, RawBone>): Skeleton {
        // Unskinned model (plain OBJ etc.): synthesize an identity root so the fallback weight in
        // makeVertex has a real bone to point at — see ModelLoader's contract.
        if (rawBones.isEmpty()) return Skeleton.singleRoot()
        return Skeleton.fromGlobalBind(rawBones.values.map { GlobalBone(it.name, it.parent, it.global) })
    }

    private class RawKey(
        val frame: Int,
        val value: Float,
        val bezier: Boolean,
        val hlx: Float, val hly: Float, // left handle (incoming), absolute (frame, value)
        val hrx: Float, val hry: Float, // right handle (outgoing)
    )

    private class RawAnim(val name: String) {
        // bone -> prop -> component -> list of keys (sorted before sampling)
        private val data = LinkedHashMap<String, LinkedHashMap<String, HashMap<Int, MutableList<RawKey>>>>()

        fun curve(bone: String, prop: String, comp: Int): MutableList<RawKey> =
            data.getOrPut(bone) { LinkedHashMap() }
                .getOrPut(prop) { HashMap() }
                .getOrPut(comp) { ArrayList() }

        fun bake(skeleton: Skeleton, fps: Float): Animation {
            var maxFrame = 0
            val channels = HashMap<Int, BoneChannel>()

            for ((boneName, props) in data) {
                val boneIdx = skeleton.indexOf(boneName)
                if (boneIdx < 0) continue

                val translations = bakeVector(props["location"], default = 0f) { f -> maxFrame = maxOf(maxFrame, f) }
                val scales = bakeVector(props["scale"], default = 1f) { f -> maxFrame = maxOf(maxFrame, f) }
                val rotations = bakeEuler(props["rotation"]) { f -> maxFrame = maxOf(maxFrame, f) }

                channels[boneIdx] = BoneChannel(
                    translations.map { Keyframe(it.first / fps, it.second) },
                    rotations.map { Keyframe(it.first / fps, it.second) },
                    scales.map { Keyframe(it.first / fps, it.second) },
                )
            }
            return Animation(name, maxFrame / fps, channels)
        }

        private inline fun bakeVector(
            comps: Map<Int, MutableList<RawKey>>?,
            default: Float,
            onFrame: (Int) -> Unit,
        ): List<Pair<Int, Vector3f>> {
            comps ?: return emptyList()
            val prepared = prepare(comps)
            return frames(comps).map { f ->
                onFrame(f)
                f to Vector3f(sample(prepared[0], f, default), sample(prepared[1], f, default), sample(prepared[2], f, default))
            }
        }

        private inline fun bakeEuler(
            comps: Map<Int, MutableList<RawKey>>?,
            onFrame: (Int) -> Unit,
        ): List<Pair<Int, Quaternionf>> {
            comps ?: return emptyList()
            val prepared = prepare(comps)
            return frames(comps).map { f ->
                onFrame(f)
                val x = sample(prepared[0], f, 0f)
                val y = sample(prepared[1], f, 0f)
                val z = sample(prepared[2], f, 0f)
                f to Quaternionf().rotationXYZ(x, y, z) // Blender default XYZ euler order
            }
        }

        private fun prepare(comps: Map<Int, MutableList<RawKey>>): Array<List<RawKey>> =
            Array(3) { comps[it]?.sortedBy { k -> k.frame } ?: emptyList() }

        // Union of key frames, or every frame when a bezier segment is present (dense bake).
        private fun frames(comps: Map<Int, MutableList<RawKey>>): List<Int> {
            val keys = comps.values.flatten()
            if (keys.isEmpty()) return emptyList()
            if (keys.any { it.bezier }) {
                return (keys.minOf { it.frame }..keys.maxOf { it.frame }).toList()
            }
            return keys.map { it.frame }.toSortedSet().toList()
        }

        // Segments use the LEFT key's interpolation mode, matching Blender.
        private fun sample(curve: List<RawKey>, frame: Int, default: Float): Float {
            if (curve.isEmpty()) return default
            if (frame <= curve.first().frame) return curve.first().value
            if (frame >= curve.last().frame) return curve.last().value
            var i = 0
            while (i < curve.lastIndex && curve[i + 1].frame < frame) i++
            val a = curve[i]
            val b = curve[i + 1]
            if (b.frame == a.frame) return a.value
            if (!a.bezier) {
                return a.value + (b.value - a.value) * (frame - a.frame) / (b.frame - a.frame)
            }
            return sampleBezier(a, b, frame.toFloat())
        }

        // Cubic bezier in (frame, value) space: P0=a, P1=a's right handle, P2=b's left, P3=b.
        private fun sampleBezier(a: RawKey, b: RawKey, frame: Float): Float {
            val x0 = a.frame.toFloat(); val x3 = b.frame.toFloat()
            // Hand-edited handles outside [x0, x3] would make x(t) non-monotonic and the
            // bisection meaningless; clamp like Blender does.
            val x1 = a.hrx.coerceIn(x0, x3); val x2 = b.hlx.coerceIn(x0, x3)
            val y0 = a.value; val y1 = a.hry; val y2 = b.hly; val y3 = b.value

            fun bez(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
                val u = 1f - t
                return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
            }

            var lo = 0f
            var hi = 1f
            repeat(24) { // ~1e-7 precision, plenty for 24fps frames
                val mid = (lo + hi) * 0.5f
                if (bez(x0, x1, x2, x3, mid) < frame) lo = mid else hi = mid
            }
            return bez(y0, y1, y2, y3, (lo + hi) * 0.5f)
        }
    }

    private val WS = Regex("\\s+")
    private val ZERO_UV = Vector2f(0f, 0f)

    private fun vec3(t: List<String>) =
        Vector3f(t[1].toFloat(), t[2].toFloat(), t[3].toFloat())

    // Reads 16 row-major floats into a JOML (column-major) matrix.
    private fun matrixRowMajor(t: List<String>, start: Int): Matrix4f {
        val c = FloatArray(16)
        for (row in 0..3) for (col in 0..3) {
            c[col * 4 + row] = t[start + row * 4 + col].toFloat()
        }
        return Matrix4f().set(c)
    }

    // endregion
}
