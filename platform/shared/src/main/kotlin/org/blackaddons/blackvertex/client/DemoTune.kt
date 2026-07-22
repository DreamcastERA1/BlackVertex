package org.blackaddons.blackvertex.client

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import org.blackaddons.blackvertex.api.attach.Attachment
import org.blackaddons.blackvertex.api.attach.AttachmentPoint
import org.blackaddons.blackvertex.render.PlayerCosmetics

/**
 * Demo-only runtime attachment tuner — iterate offsets without relaunching:
 *
 *  - `/blackvertex-tune list` — active cosmetics with indices and current attachments
 *  - `/blackvertex-tune <i> point <PRESET>` — snap to an [AttachmentPoint] preset
 *  - `/blackvertex-tune <i> offset <x> <y> <z>` / `rotate <pitch> <yaw> <roll>` / `scale <s>`
 *
 * Prints the resulting [Attachment] after each change; copy the numbers into presets or
 * the backend manifest. Also the mechanical prototype of the future cosmetics editor.
 */
internal object DemoTune {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("blackvertex-tune")
                    .then(ClientCommands.literal("list").executes { ctx -> list(ctx.source); 1 })
                    .then(
                        ClientCommands.argument("index", IntegerArgumentType.integer(0))
                            .then(
                                ClientCommands.literal("point").then(
                                    ClientCommands.argument("preset", StringArgumentType.word())
                                        .executes { ctx -> setPoint(ctx); 1 }
                                )
                            )
                            .then(
                                ClientCommands.literal("offset").then(
                                    ClientCommands.argument("x", FloatArgumentType.floatArg()).then(
                                        ClientCommands.argument("y", FloatArgumentType.floatArg()).then(
                                            ClientCommands.argument("z", FloatArgumentType.floatArg())
                                                .executes { ctx ->
                                                    tune(ctx) {
                                                        it.copy(
                                                            offsetX = FloatArgumentType.getFloat(ctx, "x"),
                                                            offsetY = FloatArgumentType.getFloat(ctx, "y"),
                                                            offsetZ = FloatArgumentType.getFloat(ctx, "z"),
                                                        )
                                                    }
                                                }
                                        )
                                    )
                                )
                            )
                            .then(
                                ClientCommands.literal("rotate").then(
                                    ClientCommands.argument("pitch", FloatArgumentType.floatArg()).then(
                                        ClientCommands.argument("yaw", FloatArgumentType.floatArg()).then(
                                            ClientCommands.argument("roll", FloatArgumentType.floatArg())
                                                .executes { ctx ->
                                                    tune(ctx) {
                                                        it.copy(
                                                            pitchDeg = FloatArgumentType.getFloat(ctx, "pitch"),
                                                            yawDeg = FloatArgumentType.getFloat(ctx, "yaw"),
                                                            rollDeg = FloatArgumentType.getFloat(ctx, "roll"),
                                                        )
                                                    }
                                                }
                                        )
                                    )
                                )
                            )
                            .then(
                                ClientCommands.literal("scale").then(
                                    ClientCommands.argument("s", FloatArgumentType.floatArg(0.01f))
                                        .executes { ctx ->
                                            tune(ctx) { it.copy(scale = FloatArgumentType.getFloat(ctx, "s")) }
                                        }
                                )
                            )
                    )
            )
        }
    }

    private fun list(source: FabricClientCommandSource) {
        val all = PlayerCosmetics.all()
        if (all.isEmpty()) {
            source.sendFeedback(Component.literal("BlackVertex: no active cosmetics"))
            return
        }
        all.forEachIndexed { i, c ->
            source.sendFeedback(Component.literal("[$i] clip=${c.clip} tex=${c.texture?.path} ${format(c.attach)}"))
        }
    }

    private fun setPoint(ctx: CommandContext<FabricClientCommandSource>) {
        val name = StringArgumentType.getString(ctx, "preset").uppercase()
        val preset = AttachmentPoint.entries.find { it.name == name }
        if (preset == null) {
            ctx.source.sendError(
                Component.literal("Unknown preset '$name'. Presets: ${AttachmentPoint.entries.joinToString { it.name }}")
            )
            return
        }
        tune(ctx) { preset.attachment }
    }

    private fun tune(ctx: CommandContext<FabricClientCommandSource>, change: (Attachment) -> Attachment): Int {
        val index = IntegerArgumentType.getInteger(ctx, "index")
        val cosmetic = PlayerCosmetics.all().getOrNull(index)
        if (cosmetic == null) {
            ctx.source.sendError(Component.literal("No cosmetic at index $index (see /blackvertex-tune list)"))
            return 0
        }
        cosmetic.attach = change(cosmetic.attach)
        ctx.source.sendFeedback(Component.literal("[$index] ${format(cosmetic.attach)}"))
        return 1
    }

    private fun format(a: Attachment): String =
        "follow=${a.follow} offset=(${a.offsetX}, ${a.offsetY}, ${a.offsetZ}) " +
            "rot=(${a.pitchDeg}, ${a.yawDeg}, ${a.rollDeg}) scale=${a.scale}"
}
