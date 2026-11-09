package org.blackaddons.blackvertex.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import org.blackaddons.blackvertex.anim.PosePalette
import org.blackaddons.blackvertex.api.InternalBlackVertexApi
import org.blackaddons.blackvertex.api.attach.Attachment
import org.blackaddons.blackvertex.api.attach.FollowPart
import org.blackaddons.blackvertex.render.gpu.BlackVertexGpu
import org.blackaddons.blackvertex.render.gpu.DrawMode
import java.util.UUID

// The feature layer that draws every registered PlayerCosmetic on a player, through the GPU path when it is available
// and the CPU skinner otherwise.
@OptIn(InternalBlackVertexApi::class)
internal class BlackVertexCosmeticLayer(
    renderer: RenderLayerParent<AvatarRenderState, PlayerModel>,
) : RenderLayer<AvatarRenderState, PlayerModel>(renderer) {

    // Reused palettes, one per (cosmetic, entity). A palette is written at submit and read by the same frame's draw,
    // and the next submit for the same key only happens after that draw is done, so overwriting it in place is safe and
    // steady frames allocate nothing.
    private val palettes = HashMap<PaletteKey, PosePalette>()

    private data class PaletteKey(val cosmetic: PlayerCosmetic, val entityId: Int)

    override fun submit(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        light: Int,
        state: AvatarRenderState,
        yRot: Float,
        xRot: Float,
    ) {
        if (state.isInvisible || state.isSpectator) return

        val cosmetics = PlayerCosmetics.active
        if (cosmetics.isEmpty()) return

        val maxDistance = PlayerCosmetics.renderDistanceBlocks
        if (state.distanceToCameraSq > maxDistance.toDouble() * maxDistance) return

        // Entries for players who left stay around. Rebuilding about 200 palettes for one frame is cheaper than
        // tracking who left.
        if (palettes.size > MAX_CACHED_PALETTES) palettes.clear()

        val overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0f)
        // Resolve the rendered player's UUID once: it targets per-player cosmetics and seeds the animation phase.
        val selfUuid = PlayerCosmetics.uuidResolver(state)
        // Double until the final wrap, because a Float alone loses frame precision after hours of uptime.
        val elapsed = (System.nanoTime() - START_NANOS) / 1_000_000_000.0 + phaseOffset(selfUuid, state.id)

        for (cosmetic in cosmetics) {
            // Per-player targeting: uuid pins the cosmetic to one player, and visibleFor is an extra check. Null means
            // draw on everyone.
            if (cosmetic.uuid != null && cosmetic.uuid != selfUuid) continue
            if (cosmetic.visibleFor?.invoke(state) == false) continue
            val palette = palettes.getOrPut(PaletteKey(cosmetic, state.id)) { PosePalette(cosmetic.model.skeleton) }
            val clip = cosmetic.clip?.let { cosmetic.model.animations[it] }
            palette.update(clip, clipTime(elapsed * cosmetic.speed, clip?.durationSeconds ?: 0f))

            val texture = cosmetic.texture ?: BlackVertexTextures.PLACEHOLDER
            val color = cosmetic.color
            // A modulator alpha below opaque turns the whole cosmetic into a translucent ghost.
            val translucent = cosmetic.translucent || (color ushr 24) != 0xFF

            poseStack.pushPose()
            applyAttachment(poseStack, cosmetic.attach)

            val gpu = BlackVertexGpu.backend?.takeIf { it.usable() }
            val gpuMesh = gpu?.meshFor(cosmetic.model, texture.path)
            if (gpu != null && gpuMesh != null) {
                val pose = poseStack.last().pose()
                val mode = if (translucent) DrawMode.TRANSLUCENT else DrawMode.CUTOUT
                gpu.submit(collector, gpuMesh, palette, pose, texture, light, overlay, mode, color)
                cosmetic.emissive?.let {
                    gpu.submit(collector, gpuMesh, palette, pose, it, light, overlay, DrawMode.EMISSIVE, color)
                }
            } else {
                fun cpuSubmit(renderType: RenderType) = BlackVertexRenderer.submit(
                    model = cosmetic.model,
                    palette = palette,
                    renderType = renderType,
                    poseStack = poseStack,
                    collector = collector,
                    light = light,
                    overlay = overlay,
                    argb = color,
                )
                cpuSubmit(
                    if (translucent) RenderTypes.entityTranslucent(texture)
                    else RenderTypes.entityCutout(texture)
                )
                // The same vanilla type that the GPU emissive pipeline copies: blending and fullbright.
                cosmetic.emissive?.let { cpuSubmit(RenderTypes.entityTranslucentEmissive(it)) }
            }
            poseStack.popPose()
        }
    }

    // Wraps the time into the clip using Doubles, and narrows only the small remainder to a Float.
    private fun clipTime(time: Double, duration: Float): Float =
        if (duration > 0f) (time % duration).toFloat() else 0f

    // A deterministic per-player phase, seeded by the entity UUID (stable across sessions). The entity id is the
    // fallback when the entity is already gone from the level.
    private fun phaseOffset(uuid: UUID?, entityId: Int): Float {
        val h = uuid?.hashCode() ?: entityId
        return (h and 0xFFFF) * 0.001f // 0..65.5s, far past any clip length
    }

    private fun applyAttachment(poseStack: PoseStack, a: Attachment) {
        when (a.follow) {
            FollowPart.BODY -> parentModel.body.translateAndRotate(poseStack)
            FollowPart.HEAD -> parentModel.head.translateAndRotate(poseStack)
            FollowPart.LEFT_ARM -> parentModel.leftArm.translateAndRotate(poseStack)
            FollowPart.RIGHT_ARM -> parentModel.rightArm.translateAndRotate(poseStack)
            FollowPart.LEFT_LEG -> parentModel.leftLeg.translateAndRotate(poseStack)
            FollowPart.RIGHT_LEG -> parentModel.rightLeg.translateAndRotate(poseStack)
            FollowPart.NONE -> {}
        }
        poseStack.translate(a.offsetX, a.offsetY, a.offsetZ)
        if (a.pitchDeg != 0f) poseStack.last().rotate(Axis.XP.rotationDegrees(a.pitchDeg))
        if (a.yawDeg != 0f) poseStack.last().rotate(Axis.YP.rotationDegrees(a.yawDeg))
        if (a.rollDeg != 0f) poseStack.last().rotate(Axis.ZP.rotationDegrees(a.rollDeg))
        if (a.scale != 1f) poseStack.scale(a.scale, a.scale, a.scale)
    }

    private companion object {
        // A shared clock, so every layer instance (wide and slim renderers) agrees on the animation time.
        val START_NANOS: Long = System.nanoTime()

        // About 2.5 times a 100-player lobby with two cosmetics each.
        const val MAX_CACHED_PALETTES = 512
    }
}
