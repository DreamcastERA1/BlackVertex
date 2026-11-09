package org.blackaddons.blackvertex.client

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import org.blackaddons.blackvertex.render.PlayerCosmetics
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Demo-only stress test, registered with the demo flag:
 *
 * - `/blackvertex-stress <count>` spawns `count` client-side [RemotePlayer]s in a grid in front of you. No server is
 *   involved, so it is pure render and skinning load, which is exactly what we want to benchmark the CPU and GPU paths
 *   with. The random UUIDs also check that the animation phase is spread per player (tails must NOT wag in sync).
 * - `/blackvertex-stress clear` removes everything that was spawned.
 *
 * Bots get ids far above live server ids, so the id map of [net.minecraft.client.multiplayer.ClientLevel] never evicts
 * a real entity.
 */
internal object DemoStress {

    private const val FAKE_ID_BASE = 0x7F000000
    private const val SPACING = 1.5

    private val spawned = ArrayList<RemotePlayer>()
    private var nextId = 0

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("blackvertex-stress")
                    .then(
                        ClientCommands.argument("count", IntegerArgumentType.integer(1, 500))
                            .executes { ctx ->
                                val count = IntegerArgumentType.getInteger(ctx, "count")
                                val ok = spawn(count)
                                ctx.source.sendFeedback(Component.literal("BlackVertex stress: +$ok bots (${spawned.size} total)"))
                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("clear").executes { ctx ->
                            val removed = clear()
                            ctx.source.sendFeedback(Component.literal("BlackVertex stress: removed $removed bots"))
                            1
                        }
                    )
                    // A benchmark helper that separates the cost of the cosmetics from vanilla player rendering.
                    .then(
                        ClientCommands.literal("cosmetics")
                            .then(ClientCommands.literal("on").executes { ctx -> setCosmetics(ctx.source, 32f); 1 })
                            .then(ClientCommands.literal("off").executes { ctx -> setCosmetics(ctx.source, 0f); 1 })
                    )
            )
        }
    }

    private fun spawn(count: Int): Int {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return 0
        val anchor = mc.player ?: return 0

        val cols = ceil(sqrt(count.toDouble())).toInt()
        for (i in 0 until count) {
            val bot = RemotePlayer(level, GameProfile(UUID.randomUUID(), "Bot$nextId"))
            bot.id = FAKE_ID_BASE + nextId
            nextId++

            val x = anchor.x + (i % cols - (cols - 1) * 0.5) * SPACING
            val z = anchor.z + 3.0 + (i / cols) * SPACING
            val yaw = (nextId * 37 % 360).toFloat() - 180f
            bot.snapTo(x, anchor.y, z, yaw, 0f)
            bot.setOldPosAndRot()
            bot.setYBodyRot(yaw)
            bot.setYHeadRot(yaw)

            level.addEntity(bot)
            spawned.add(bot)
        }
        return count
    }

    private fun setCosmetics(source: FabricClientCommandSource, distance: Float) {
        PlayerCosmetics.renderDistanceBlocks = distance
        source.sendFeedback(Component.literal("BlackVertex cosmetics ${if (distance > 0f) "ON" else "OFF"}"))
    }

    private fun clear(): Int {
        val level = Minecraft.getInstance().level
        var removed = 0
        for (bot in spawned) {
            if (level != null && level.getEntity(bot.id) === bot) {
                level.removeEntity(bot.id, Entity.RemovalReason.DISCARDED)
                removed++
            }
        }
        spawned.clear()
        return removed
    }
}
