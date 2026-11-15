package dev.modreloader.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.modreloader.ModReloader;
import dev.modreloader.client.ClientCommands;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.ui.ModReloaderI18n;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.util.List;

/**
 * The {@code /modreloader} command tree, shared by both sides.
 *
 * <p>The client adds a {@code gui} node on top ({@link ClientCommands}); a
 * dedicated server only gets the console-safe nodes.
 */
public final class ReloaderCommands {
    /** Dev-only: captured so {@code AddCommandCheck} can execute without a chat round-trip. */
    private static CommandDispatcher<CommandSourceStack> lastDispatcher;

    private ReloaderCommands() {
    }

    public static CommandDispatcher<CommandSourceStack> dispatcher() {
        return lastDispatcher;
    }

    public static void registerClient(RegisterClientCommandsEvent event) {
        lastDispatcher = event.getDispatcher();
        event.getDispatcher().register(ClientCommands.withGui(tree()));
    }

    public static void registerServer(RegisterCommandsEvent event) {
        event.getDispatcher().register(tree());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("modreloader")
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
                                })));
    }
}
