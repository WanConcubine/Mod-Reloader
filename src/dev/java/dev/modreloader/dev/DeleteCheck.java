package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Dev-only check for jars that appear and disappear ({@code -PverifyDelete=true}).
 *
 * <p>Asserts: a dropped jar becomes pending; deleting it removes it from the
 * pending list; re-copying the same bytes/mtime brings it back (the {@code seen}
 * fingerprint must not shadow it); and loading a vanished path reports
 * {@code MISSING_FILE} instead of an unclassified failure.
 */
public final class DeleteCheck {
    private enum Stage { WAIT_WATCHER, DROP, WAIT_PENDING, DELETE, WAIT_GONE, RECOPY, WAIT_BACK, LOAD_GONE, DONE }

    private static final int TIMEOUT_TICKS = 20 * 120;

    private static final String SAMPLE = "/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar";

    private static Stage stage = Stage.WAIT_WATCHER;
    private static int ticks;
    private static boolean started;
    private static boolean failed;
    private static Path jar;

    private DeleteCheck() {
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[DeleteCheck] started");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            fail("timeout at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_WATCHER -> waitWatcher();
            case DROP -> drop();
            case WAIT_PENDING -> waitPending();
            case DELETE -> delete();
            case WAIT_GONE -> waitGone();
            case RECOPY -> recopy();
            case WAIT_BACK -> waitBack();
            case LOAD_GONE -> loadGone();
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void waitWatcher() {
        if (ModReloader.WATCHER == null) {
            return;
        }
        jar = ModReloader.WATCHER.inboxDir().resolve("deletecheck-EasyLAN.jar");
        stage = Stage.DROP;
        ticks = 0;
    }

    private static void drop() {
        try {
            Files.createDirectories(jar.getParent());
            Files.copy(Path.of(SAMPLE), jar, StandardCopyOption.REPLACE_EXISTING);
            ModReloader.LOGGER.info("[DeleteCheck] dropped {}", jar.getFileName());
        } catch (Throwable t) {
            fail("cannot copy the sample jar: " + t);
            return;
        }
        stage = Stage.WAIT_PENDING;
        ticks = 0;
    }

    private static void waitPending() {
        List<ModsFolderWatcher.Candidate> pending = ModReloader.WATCHER.scanNow();
        if (contains(pending)) {
            ModReloader.LOGGER.info("[DeleteCheck] jar is pending after drop");
            stage = Stage.DELETE;
            ticks = 0;
            return;
        }
        if (ticks > 20 * 30) {
            fail("dropped jar never became pending");
        }
    }

    private static void delete() {
        try {
            Files.deleteIfExists(jar);
            ModReloader.LOGGER.info("[DeleteCheck] deleted {}", jar.getFileName());
        } catch (Throwable t) {
            fail("cannot delete the jar: " + t);
            return;
        }
        stage = Stage.WAIT_GONE;
        ticks = 0;
    }

    private static void waitGone() {
        if (ticks < 60) {
            return;
        }
        List<ModsFolderWatcher.Candidate> pending = ModReloader.WATCHER.scanNow();
        if (contains(pending)) {
            fail("deleted jar is still listed in the pending list");
            return;
        }
        ModReloader.LOGGER.info("[DeleteCheck] deleted jar is no longer pending");
        stage = Stage.RECOPY;
        ticks = 0;
    }

    private static void recopy() {
        try {
            // Same bytes and mtime: this is the case a stale "seen" entry would shadow.
            Files.copy(Path.of(SAMPLE), jar, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            ModReloader.LOGGER.info("[DeleteCheck] re-copied {} with attributes", jar.getFileName());
        } catch (Throwable t) {
            fail("cannot re-copy the jar: " + t);
            return;
        }
        stage = Stage.WAIT_BACK;
        ticks = 0;
    }

    private static void waitBack() {
        List<ModsFolderWatcher.Candidate> pending = ModReloader.WATCHER.scanNow();
        if (contains(pending)) {
            ModReloader.LOGGER.info("[DeleteCheck] re-copied jar is pending again (seen entry did not shadow it)");
            stage = Stage.LOAD_GONE;
            ticks = 0;
            return;
        }
        if (ticks > 20 * 30) {
            fail("re-copied jar never became pending again; a stale seen entry may be shadowing it");
        }
    }

    private static void loadGone() {
        Path gone = jar.getParent().resolve("definitely-gone.jar");
        HotAddEngine.Result result = HotAddEngine.add(gone);
        ModReloader.LOGGER.info("[DeleteCheck] add(missing) -> ok={}, stage={}, kind={}, message={}",
                result.ok(), result.stage(), result.kind(), result.message());
        if (result.ok()) {
            fail("loading a missing file reported success");
            return;
        }
        if (result.kind() != HotAddEngine.FailureKind.MISSING_FILE) {
            fail("missing file reported kind " + result.kind() + " instead of MISSING_FILE");
            return;
        }
        try {
            Files.deleteIfExists(jar);
        } catch (Throwable ignored) {
            // Best effort cleanup.
        }
        ModReloader.LOGGER.info("[DeleteCheck] RESULT: PASS");
        stage = Stage.DONE;
        ticks = 0;
    }

    private static boolean contains(List<ModsFolderWatcher.Candidate> pending) {
        return pending.stream().anyMatch(candidate -> candidate.path().equals(jar));
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            ModReloader.LOGGER.info("[DeleteCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[DeleteCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[DeleteCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
