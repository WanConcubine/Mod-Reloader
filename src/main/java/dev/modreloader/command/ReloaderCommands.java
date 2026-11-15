package dev.modreloader.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.ui.ModReloaderI18n;
import dev.modreloader.ui.ModReloaderScreen;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.nio.file.Path;
import java.util.List;

public final class ReloaderCommands {
    private ReloaderCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("modreloader")
                .then(Commands.literal("ping")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("modreloader.command.pong"), false);
                            return 1;
                        }))
                .then(Commands.literal("scan")
                        .executes(ctx -> {
                            if (ModReloader.WATCHER == null) {
                                ctx.getSource().sendFailure(Component.translatable("modreloader.command.watcher_not_started"));
                                return 0;
                            }
                            List<ModsFolderWatcher.Candidate> candidates = ModReloader.WATCHER.scanNow();
                            if (candidates.isEmpty()) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("modreloader.command.no_new_mods"), false);
                                return 0;
                            }
                            for (ModsFolderWatcher.Candidate candidate : candidates) {
                                Component line = Component.literal("[ModReloader] ")
                                        .append(candidate.path().getFileName().toString())
                                        .append(Component.literal(" · "))
                                        .append(ModReloaderI18n.summary(candidate.inspection()))
                                        .append(Component.literal(" · "))
                                        .append(ModReloaderI18n.blockReason(candidate.inspection()));
                                ctx.getSource().sendSuccess(() -> line, false);
                            }
                            return candidates.size();
                        }))
                .then(Commands.literal("gui")
                        .executes(ctx -> {
                            if (ModReloader.WATCHER == null) {
                                ctx.getSource().sendFailure(Component.translatable("modreloader.command.watcher_not_started"));
                                return 0;
                            }
                            List<ModsFolderWatcher.Candidate> candidates = ModReloader.WATCHER.scanNow();
                            if (candidates.isEmpty()) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("modreloader.command.no_new_mods"), false);
                                return 0;
                            }
                            Minecraft.getInstance().setScreen(new ModReloaderScreen(candidates));
                            return 1;
                        }))
                .then(Commands.literal("probe")
                        .then(Commands.argument("jar", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "jar");
                                    List<String> lines = MixinProbe.probe(Path.of(raw));
                                    lines.forEach(line -> ctx.getSource().sendSuccess(
                                            () -> Component.literal(line), false));
                                    return 1;
                                })))
                .then(Commands.literal("add")
                        .then(Commands.argument("jar", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "jar");
                                    try {
                                        HotAddEngine.Result result = HotAddEngine.add(Path.of(raw));
                                        ctx.getSource().sendSuccess(
                                                () -> Component.translatable(result.ok()
                                                ? "modreloader.command.add.success"
                                                : "modreloader.command.add.failed", result.message()),
                                                false);
                                        return result.ok() ? 1 : 0;
                                    } catch (Throwable t) {
                                        ctx.getSource().sendFailure(
                                                Component.translatable("modreloader.command.exception", String.valueOf(t)));
                                        return 0;
                                    }
                                }))));
    }
}
