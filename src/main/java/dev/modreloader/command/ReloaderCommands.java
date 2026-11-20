package dev.modreloader.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.i18n.ModReloaderI18n;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@code /modreloader} command tree, shared by both sides.
 *
 * <p>The client decorates this tree with its {@code gui} node in
 * {@code client/ClientCommands} ({@code ClientBootstrap} subscribes there): the
 * dependency direction is client → command, so this package stays free of client
 * types and a dedicated server never references them.
 */
public final class ReloaderCommands {
    /** Dev-only: captured so {@code AddCommandCheck} can execute without a chat round-trip. */
    private static CommandDispatcher<CommandSourceStack> lastDispatcher;

    private ReloaderCommands() {
    }

    public static CommandDispatcher<CommandSourceStack> dispatcher() {
        return lastDispatcher;
    }

    /** Remembers the client dispatcher for the dev harness (the tree is registered by the client). */
    public static void remember(CommandDispatcher<CommandSourceStack> dispatcher) {
        lastDispatcher = dispatcher;
    }

    public static void registerServer(RegisterCommandsEvent event) {
        event.getDispatcher().register(tree());
    }

    /**
     * TAB completion for {@code add}: every jar name in mods/ and the inbox.
     *
     * <p>Directory listing only — no mod inspection — because the console asks for
     * suggestions on every keystroke of the last word.
     */
    private static final SuggestionProvider<CommandSourceStack> JAR_SUGGESTIONS = (ctx, builder) -> {
        Set<String> names = new LinkedHashSet<>();
        for (Path dir : jarSearchDirs()) {
            try (Stream<Path> entries = Files.list(dir)) {
                entries.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".jar"))
                        .sorted()
                        .forEach(names::add);
            } catch (IOException | RuntimeException ignored) {
                // Missing directory or a permission problem: no suggestions, not an error.
            }
        }
        for (String name : names) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    /** Where {@code add <name>} looks for a bare file name, in order. */
    private static List<Path> jarSearchDirs() {
        return List.of(
                FMLPaths.MODSDIR.get(),
                FMLPaths.GAMEDIR.get().resolve("modreloader/inbox"));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
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
                                        .append(ModReloaderI18n.blockReason(candidate.inspection(),
                                                dev.modreloader.capability.Capabilities.available(
                                                        dev.modreloader.capability.Capabilities.Capability.REGISTRY_ADAPTATION),
                                                dev.modreloader.hotadd.LateAccessTransformers.enabled()))
                                        .append(Component.literal(" "))
                                        .append(Component.translatable("modreloader.command.scan.hint",
                                                candidate.path().getFileName().toString()));
                                ctx.getSource().sendSuccess(() -> line, false);
                            }
                            return candidates.size();
                        }))
                .then(Commands.literal("jbr")
                        .executes(ctx -> {
                            for (String line : dev.modreloader.inspect.JbrSupport.diagnose()) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal(line), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("probe")
                        .then(Commands.argument("jar", StringArgumentType.greedyString())
                                .suggests(JAR_SUGGESTIONS)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "jar");
                                    JarPathResolver.Resolution resolution =
                                            JarPathResolver.resolve(raw, jarSearchDirs());
                                    List<String> lines = MixinProbe.probe(resolution.found()
                                            ? resolution.file()
                                            : Path.of(raw).toAbsolutePath().normalize());
                                    lines.forEach(line -> ctx.getSource().sendSuccess(
                                            () -> Component.literal(line), false));
                                    return 1;
                                })))
                .then(Commands.literal("why")
                        .then(Commands.argument("jar", StringArgumentType.greedyString())
                                .suggests(JAR_SUGGESTIONS)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "jar");
                                    JarPathResolver.Resolution resolution =
                                            JarPathResolver.resolve(raw, jarSearchDirs());
                                    if (!resolution.found()) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "modreloader.command.add.not_found", raw,
                                                resolution.searched().stream()
                                                        .map(Path::toString)
                                                        .toList().toString()));
                                        return 0;
                                    }
                                    // Read-only diagnosis: the command succeeded even when the
                                    // verdict is "refused", so the console does not see a failure.
                                    for (String line : dev.modreloader.inspect.HotAddWhy
                                            .of(resolution.file()).lines()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal(line), false);
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("why")
                        .then(Commands.argument("jar", StringArgumentType.greedyString())
                                .suggests(JAR_SUGGESTIONS)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "jar");
                                    JarPathResolver.Resolution resolution =
                                            JarPathResolver.resolve(raw, jarSearchDirs());
                                    if (!resolution.found()) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "modreloader.command.add.not_found", raw,
                                                resolution.searched().stream()
                                                        .map(Path::toString)
                                                        .toList().toString()));
                                        return 0;
                                    }
                                    // Read-only diagnosis: the command succeeded even when the
                                    // verdict is "refused", so the console does not see a failure.
                                    for (String line : dev.modreloader.inspect.HotAddWhy
                                            .of(resolution.file()).lines()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal(line), false);
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("add")
                        .then(Commands.argument("jar", StringArgumentType.greedyString())
                                .suggests(JAR_SUGGESTIONS)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "jar");
                                    JarPathResolver.Resolution resolution =
                                            JarPathResolver.resolve(raw, jarSearchDirs());
                                    if (!resolution.found()) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "modreloader.command.add.not_found", raw,
                                                resolution.searched().stream()
                                                        .map(Path::toString)
                                                        .toList().toString()));
                                        return 0;
                                    }
                                    try {
                                        HotAddEngine.Result result = HotAddEngine.add(resolution.file());
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
