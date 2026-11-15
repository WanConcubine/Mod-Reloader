package dev.modreloader.servertest;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Realistic server-side fixture: does what real server mods do on construction —
 * registers a command and a repeating tick listener.
 *
 * <p>Used to answer "is server hot-add actually useful?" instead of proving a
 * trivial constructor runs.
 */
@Mod(ServerTestMod.MOD_ID)
public final class ServerTestMod {
    public static final String MOD_ID = "servertest";
    private static final Logger LOGGER = LoggerFactory.getLogger("ServerTest");
    private static int ticks;

    public ServerTestMod() {
        NeoForge.EVENT_BUS.addListener(ServerTestMod::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ServerTestMod::onServerTick);
        LOGGER.info("[ServerTest] @Mod constructed; modListIsLoaded={}", ModList.get().isLoaded(MOD_ID));
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("servertest")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("[ServerTest] command works"), false);
                    return 1;
                });
        event.getDispatcher().register(command);
        LOGGER.info("[ServerTest] RegisterCommandsEvent fired; /servertest registered");
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks % 100 != 0) {
            return;
        }
        LOGGER.info("[ServerTest] ServerTickEvent received {} times (future events reach this mod)",
                ticks);
    }
}
