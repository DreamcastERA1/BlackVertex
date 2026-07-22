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
 * A BlackVertex model attached to players as a cosmetic (ears, tail, …).
 *
 * @param model       the parsed mesh + skeleton + clips
 * @param clip        animation clip name to play, or null for the static bind pose
 * @param texture     texture to sample; null falls back to a placeholder
 * @param emissive    optional glow layer: same mesh drawn fullbright (unlit) on top,
 *                    sampling this texture — its alpha is the glow mask
 * @param translucent draw the base with alpha blending instead of alpha cutout
 * @param color       ARGB modulator over the whole cosmetic (0xFFFFFFFF = untouched); an alpha
 *                    below 0xFF renders it as a translucent "ghost", the RGB tints it. Mutable so
 *                    a fade or flash can be driven per frame.
 * @param speed       animation playback speed multiplier (1 = authored speed)
 * @param attach      where/how the model sits on the player; mutable for runtime tuning
 * @param uuid        draw only on the player with this UUID; null = every player (the demo default)
 * @param visibleFor  extra per-player gate on the rendered state; null = no extra gate. Combined with
 *                    [uuid] by AND — the cosmetic draws only when both pass.
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
 * Registry of player cosmetics and the Fabric hook that installs the render layer
 * onto the player renderer. Cosmetics can be added at any time (e.g. streamed from a
 * backend); the layer reads this list every frame.
 */
object PlayerCosmetics {

    /**
     * Cosmetics on players farther than this many blocks from the camera are skipped
     * entirely (they are a few pixels tall there anyway). The consuming mod may override;
     * `Float.MAX_VALUE` disables the cut-off.
     */
    var renderDistanceBlocks: Float = 32f

    /**
     * How the layer maps a render state to the player's UUID (for per-player targeting). The default
     * reads the world entity by its id, which fails for an off-world preview — a GUI portrait of an
     * unspawned fake player. A consuming mod that stashes the UUID on the render state (via a mixin)
     * can point this at that, so previews target correctly too.
     */
    var uuidResolver: (AvatarRenderState) -> UUID? = { state ->
        Minecraft.getInstance().level?.getEntity(state.id)?.uuid
    }

    // The layer iterates this every frame; snapshot semantics of COW keep add/remove safe
    // from any thread. Exposed internally to avoid a defensive copy per player per frame.
    internal val active = CopyOnWriteArrayList<PlayerCosmetic>()

    fun add(cosmetic: PlayerCosmetic) {
        active.add(cosmetic)
    }

    /** Remove a previously added cosmetic; frees its GPU buffers once unreferenced. */
    fun remove(cosmetic: PlayerCosmetic) {
        active.remove(cosmetic)
        BlackVertexGpu.backend?.releaseUnused(active.map { it.model })
    }

    /** Remove all cosmetics (e.g. on backend re-sync). */
    fun clear() {
        active.clear()
        BlackVertexGpu.backend?.releaseUnused(emptyList())
    }

    /** Snapshot of the active cosmetics. */
    fun all(): List<PlayerCosmetic> = active.toList()

    /** Register the feature layer on the player renderer. Call once from client init. */
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
