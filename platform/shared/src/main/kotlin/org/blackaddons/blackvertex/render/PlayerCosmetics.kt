package org.blackaddons.blackvertex.render

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.resources.Identifier
import org.blackaddons.blackvertex.api.attach.Attachment
import org.blackaddons.blackvertex.api.model.Model
import org.blackaddons.blackvertex.render.gpu.BlackVertexGpu
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A BlackVertex model attached to players as a cosmetic, like ears or a tail.
 *
 * @param model the parsed mesh, skeleton and clips.
 * @param clip the animation clip to play, or null for the static bind pose.
 * @param texture the texture to sample. Null falls back to a placeholder.
 * @param emissive an optional glow layer: the same mesh drawn fullbright (unlit) on top, sampling this texture. Its
 *     alpha is the glow mask.
 * @param translucent draws the base with alpha blending instead of alpha cutout.
 * @param color an ARGB modulator over the whole cosmetic (0xFFFFFFFF leaves it unchanged). An alpha below 0xFF draws it
 *     as a translucent "ghost", and the RGB tints it. Mutable, so a fade or flash can be driven per frame.
 * @param speed the animation playback speed multiplier (1 is the authored speed).
 * @param attach where and how the model sits on the player. Mutable for tuning at runtime.
 * @param uuid draws only on the player with this UUID. Null draws on every player (the demo default).
 * @param visibleFor an extra per-player check on the rendered state. Null means no extra check. Both this and [uuid]
 *     must pass for the cosmetic to draw.
 */
class PlayerCosmetic(
    val model: Model,
    val clip: String? = null,
    val texture: Identifier? = null,
    val emissive: Identifier? = null,
    val translucent: Boolean = false,
    var color: Int = 0xFFFFFFFF.toInt(),
    val speed: Float = 1f,
    var attach: Attachment = Attachment(),
    val uuid: UUID? = null,
    val visibleFor: ((AvatarRenderState) -> Boolean)? = null,
)

/**
 * The registry of player cosmetics and the Fabric hook that adds the render layer to the player renderer. Cosmetics can
 * be added at any time (streamed from a backend, for example), and the layer reads this list every frame.
 */
object PlayerCosmetics {

    /**
     * Cosmetics on players farther than this many blocks from the camera are skipped entirely, since they are only a
     * few pixels tall there. The consuming mod may change it, and `Float.MAX_VALUE` turns the cut-off off.
     */
    var renderDistanceBlocks: Float = 32f

    /**
     * How the layer gets the player's UUID from a render state, for per-player targeting. The default reads the world
     * entity by its id, which fails for a preview outside the world, like a GUI portrait of a fake player that was
     * never spawned. A consuming mod that stores the UUID on the render state (with a mixin) can point this at it, so
     * previews target correctly too.
     */
    var uuidResolver: (AvatarRenderState) -> UUID? = { state ->
        Minecraft.getInstance().level?.getEntity(state.id)?.uuid
    }

    // The layer goes through this every frame. CopyOnWriteArrayList snapshots keep adding and removing safe from any
    // thread. It is internal so the layer does not need a defensive copy per player per frame.
    internal val active = CopyOnWriteArrayList<PlayerCosmetic>()

    fun add(cosmetic: PlayerCosmetic) {
        active.add(cosmetic)
    }

    /** Removes a previously added cosmetic and frees its GPU buffers once nothing references them. */
    fun remove(cosmetic: PlayerCosmetic) {
        active.remove(cosmetic)
        BlackVertexGpu.backend?.releaseUnused(active.map { it.model })
    }

    /** Removes all cosmetics, for example when syncing with the backend again. */
    fun clear() {
        active.clear()
        BlackVertexGpu.backend?.releaseUnused(emptyList())
    }

    /** A snapshot of the active cosmetics. */
    fun all(): List<PlayerCosmetic> = active.toList()

    /** Registers the feature layer on the player renderer. Call it once from client init. */
    fun init() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register(
            LivingEntityRenderLayerRegistrationCallback { _, renderer, helper, _ ->
                if (renderer is AvatarRenderer) {
                    @Suppress("UNCHECKED_CAST")
                    val parent = renderer as RenderLayerParent<AvatarRenderState, PlayerModel>
                    helper.register(BlackVertexCosmeticLayer(parent))
                }
            }
        )
    }
}
