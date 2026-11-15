package dev.modreloader.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.modreloader.ModReloader;
import dev.modreloader.ui.ModReloaderScreen;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;

/**
 * Client-only command node. Kept out of {@code ReloaderCommands} so the shared
 * command tree stays free of client types.
 */
public final class ClientCommands {
    private ClientCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> withGui(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("gui")
                .executes(ctx -> {
                    if (ModReloader.WATCHER == null) {
                        ctx.getSource().sendFailure(
                                net.minecraft.network.chat.Component.translatable(
                                        "modreloader.command.watcher_not_started"));
                        return 0;
                    }
                    List<ModsFolderWatcher.Candidate> candidates = ModReloader.WATCHER.scanNow();
                    if (candidates.isEmpty()) {
                        ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component.translatable(
                                        "modreloader.command.no_new_mods"), false);
                        return 0;
                    }
                    Minecraft.getInstance().setScreen(new ModReloaderScreen(candidates));
                    return 1;
                }));
    }
}
