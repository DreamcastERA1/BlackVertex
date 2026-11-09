package org.blackaddons.blackvertex.format.geo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.blackaddons.blackvertex.api.ModelLoader
import org.blackaddons.blackvertex.api.model.GlobalBone
import org.blackaddons.blackvertex.api.model.Mesh
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.api.model.Skeleton
import org.blackaddons.blackvertex.format.cuboid.CuboidMesh
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * [ModelLoader] for Bedrock and GeckoLib geometry (`.geo.json` as Blockbench exports it). It supports bones (a pivot
 * hierarchy), box UV cubes and inflate. Animations come from separate `.animation.json` files, which are not part of
 * the geometry and are loaded later.
 *
 * Known simplifications, each of which throws or is noted, never silently wrong:
 * - Per-face UV maps are rejected (box UV only).
 * - `mirror` is ignored, and the rest `rotation` of cubes and bones is applied as XYZ Euler. Check it against a real
 *   Blockbench export and calibrate it if the handedness differs.
 */
object GeoJsonLoader : ModelLoader {

    override val extensions: Set<String> = setOf("json")

    override fun load(text: String): Model {
        val root = JsonParser.parseString(text).asJsonObject
        val geometry = root.getAsJsonArray("minecraft:geometry")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("not a bedrock geometry file (no minecraft:geometry)")
        val desc = geometry.getAsJsonObject("description")
        val texW = desc?.get("texture_width")?.asInt ?: 64
        val texH = desc?.get("texture_height")?.asInt ?: 64

        val boneJsons = geometry.getAsJsonArray("bones")?.map { it.asJsonObject } ?: emptyList()

        // Each bone's frame sits at its pivot, so animations rotate around the right point. The rest rotation is baked
        // into the cube geometry below, not into the bind matrix.
        val skeleton = Skeleton.fromGlobalBind(
            boneJsons.map { b ->
                val pivot = vec(b, "pivot")
                GlobalBone(
                    b.get("name").asString,
                    b.get("parent")?.asString,
                    Matrix4f().translation(pivot.x / 16f, pivot.y / 16f, pivot.z / 16f),
                )
            }
        )

        val meshes = ArrayList<Mesh>()
        boneJsons.forEachIndexed { boneIdx, b ->
            val boneName = b.get("name").asString
            val restRotation = b.get("rotation")?.let { vec(b, "rotation") }
            val bonePivot = vec(b, "pivot")
            b.getAsJsonArray("cubes")?.forEachIndexed { ci, cubeEl ->
                val cube = cubeEl.asJsonObject
                if (cube.get("uv")?.isJsonObject == true) {
                    throw IllegalArgumentException("bone '$boneName' cube $ci uses per-face UV; only box UV is supported")
                }
                val origin = vec(cube, "origin")
                val size = vec(cube, "size")
                val uv = cube.getAsJsonArray("uv")
                val mesh = CuboidMesh.mesh(
                    name = "$boneName/$ci",
                    from = origin,
                    to = Vector3f(origin).add(size),
                    uv = (uv?.get(0)?.asInt ?: 0) to (uv?.get(1)?.asInt ?: 0),
                    textureWidth = texW,
                    textureHeight = texH,
                    inflate = cube.get("inflate")?.asFloat ?: 0f,
                    bone = boneIdx,
                )
                // Bakes the rest rotations (the cube's own, then the bone's) into the bind pose.
                rotate(mesh, cube.get("rotation")?.let { vec(cube, "rotation") }, cube.get("pivot")?.let { vec(cube, "pivot") } ?: origin)
                rotate(mesh, restRotation, bonePivot)
                meshes.add(mesh)
            }
        }
        return Model(meshes, skeleton, emptyMap())
    }

    private fun rotate(mesh: Mesh, degrees: Vector3f?, pivotPx: Vector3f) {
        if (degrees == null || (degrees.x == 0f && degrees.y == 0f && degrees.z == 0f)) return
        val m = Matrix4f()
            .translation(pivotPx.x / 16f, pivotPx.y / 16f, pivotPx.z / 16f)
            .rotateXYZ(
                Math.toRadians(degrees.x.toDouble()).toFloat(),
                Math.toRadians(degrees.y.toDouble()).toFloat(),
                Math.toRadians(degrees.z.toDouble()).toFloat(),
            )
            .translate(-pivotPx.x / 16f, -pivotPx.y / 16f, -pivotPx.z / 16f)
        for (v in mesh.vertices) {
            m.transformPosition(v.position)
            m.transformDirection(v.normal).normalize()
        }
    }

    private fun vec(o: JsonObject, key: String): Vector3f {
        val a = o.getAsJsonArray(key) ?: return Vector3f()
        return Vector3f(a[0].asFloat, a[1].asFloat, a[2].asFloat)
    }
}
