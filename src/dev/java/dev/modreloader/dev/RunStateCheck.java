package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.RunState;
import dev.modreloader.hotadd.RunStateWarner;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * Dev-only check for {@link RunState}: a failure after the registry commit must
 * mark the process dirty, and the next add must be refused before anything is
 * touched.
 *
 * <p>Needs a jar with registry content and {@code -PfailAfterRegistry=true}
 * (client: {@code -PverifyRunState=<jar>}, server: {@code -PserverRunState=<jar>}).
 * On the server the harness also waits for the periodic console warning
 * ({@code -PdirtyLogSeconds=2}) and asserts it repeats.
 */
public final class RunStateCheck {
    private static final int START_TICKS = 60;
    private static final int WARN_WINDOW_TICKS = 120;

    private static Path jar;
    private static boolean client;
    private static boolean started;
    private static boolean checksDone;
    private static boolean ok = true;
    private static int ticks;
    private static int waitTicks;
    private static long warnLogsBefore;
    private static MinecraftServer server;

    private RunStateCheck() {
    }

    public static void startClient(Path jarPath) {
        if (started) {
            return;
        }
        started = true;
        client = true;
        jar = jarPath.toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[RunStateCheck] armed with {} (client)", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    public static void startServer(String jarPath) {
        if (started) {
            return;
        }
        started = true;
        client = false;
        jar = Path.of(jarPath).toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[RunStateCheck] armed with {} (dedicated server)", jar);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> server = event.getServer());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        if (checksDone) {
            if (client || server == null || ++waitTicks < WARN_WINDOW_TICKS) {
                return;
            }
            finish();
            return;
        }
        if (client) {
            if (++ticks < START_TICKS) {
                return;
            }
        } else if (server == null || ++ticks < START_TICKS) {
            return;
        }

        HotAddEngine.Result first = HotAddEngine.add(jar);
        ok &= expect(!first.ok(), "injected failure must fail the add");
        ok &= expect(RunState.isDirty(), "a post-commit failure must mark the runtime dirty");
        ok &= expect(first.message().contains("runtime is dirty"),
                "failure message must say the runtime is dirty: " + first.message());
        ModReloader.LOGGER.info("[RunStateCheck] first add: ok={} kind={} message={}",
                first.ok(), first.kind(), first.message());

        warnLogsBefore = RunStateWarner.logCount();
        HotAddEngine.Result second = HotAddEngine.add(jar);
        ok &= expect(!second.ok(), "the second add must be refused");
        ok &= expect(second.kind() == HotAddEngine.FailureKind.RUNTIME_DIRTY,
                "second add must fail as RUNTIME_DIRTY, was " + second.kind());
        ok &= expect(second.stage() == HotAddEngine.Stage.VALIDATE,
                "second add must stop at VALIDATE, was " + second.stage());
        ModReloader.LOGGER.info("[RunStateCheck] second add: ok={} kind={} stage={} message={}",
                second.ok(), second.kind(), second.stage(), second.message());

        checksDone = true;
        if (client) {
            finish();
        } else {
            ModReloader.LOGGER.info(
                    "[RunStateCheck] waiting {} ticks for the periodic console warning", WARN_WINDOW_TICKS);
        }
    }

    private static void finish() {
        if (!client) {
            long logged = RunStateWarner.logCount() - warnLogsBefore;
            ok &= expect(logged >= 2,
                    "dirty warning must repeat on the console, saw " + logged + " line(s)");
            ModReloader.LOGGER.info("[RunStateCheck] periodic warnings during the window: {}", logged);
        }
        ModReloader.LOGGER.info("[RunStateCheck] RESULT: {}", ok ? "PASS" : "FAIL");
        started = false;
        if (client) {
            Minecraft.getInstance().stop();
        } else if (server != null) {
            server.halt(false);
        }
    }

    private static boolean expect(boolean condition, String failure) {
        if (!condition) {
            ModReloader.LOGGER.error("[RunStateCheck] FAIL: {}", failure);
        }
        return condition;
    }
}