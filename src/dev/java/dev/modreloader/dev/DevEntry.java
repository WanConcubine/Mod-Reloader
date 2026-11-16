package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Entry point of the dev-only verification harnesses.
 *
 * <p>Lives in {@code src/dev/java}, a separate source set: none of this is in
 * the production jar, and the main source set cannot reference it (the call from
 * {@code ClientBootstrap} is by name only). Every harness is still opt-in
 * through its own system property.
 */
public final class DevEntry {
    private static boolean installed;
    private static int autoAddTicks;
    private static boolean autoAddDone;

    private DevEntry() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        installAutoAdd();
        installChecks();
    }

    private static void installAutoAdd() {
        String autoAdd = System.getProperty("modreloader.autoAdd", "").trim();
        boolean verifyLan = Boolean.getBoolean("modreloader.verifyLan");
        boolean verifyRollback = Boolean.getBoolean("modreloader.verifyRollback");
        if (autoAdd.isEmpty()) {
            if (verifyLan || verifyRollback) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] verifyLan/verifyRollback need autoAdd; not starting the check");
            }
            return;
        }
        Path jar = Path.of(autoAdd);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            if (autoAddDone) {
                return;
            }
            if (++autoAddTicks < 100) {
                return;
            }
            autoAddDone = true;
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[ModReloader] autoAdd result: {}", result.message());
            if (verifyRollback) {
                RollbackCheck.start(jar, result);
            } else if (verifyLan && result.ok()) {
                LanScreenCheck.start();
            }
        });
    }

    private static void installChecks() {
        if (Boolean.getBoolean("modreloader.verifyMenu")) {
            MenuEntryCheck.start();
        }
        if (Boolean.getBoolean("modreloader.verifyDelete")) {
            DeleteCheck.start();
        }
        if (Boolean.getBoolean("modreloader.verifyRegistryProbe")) {
            RegistryProbeCheck.start();
        }
        String verifyPayloadRemote = System.getProperty("modreloader.verifyPayloadRemote", "").trim();
        if (!verifyPayloadRemote.isEmpty()) {
            RemotePayloadCheck.start(Path.of(verifyPayloadRemote));
        }
        String verifyPayload = System.getProperty("modreloader.verifyPayload", "").trim();
        if (!verifyPayload.isEmpty()) {
            PayloadCheck.start(Path.of(verifyPayload));
        }
        String verifyAddCommand = System.getProperty("modreloader.verifyAddCommand", "").trim();
        if (!verifyAddCommand.isEmpty()) {
            AddCommandCheck.start(Path.of(verifyAddCommand));
        }
        String verifyCompat = System.getProperty("modreloader.verifyCompat", "").trim();
        if (!verifyCompat.isEmpty()) {
            CompatCheck.start(verifyCompat);
        }
        String watchJar = System.getProperty("modreloader.watchJar", "").trim();
        if (Boolean.getBoolean("modreloader.verifyWatch")) {
            if (watchJar.isEmpty()) {
                ModReloader.LOGGER.warn("[ModReloader] verifyWatch needs watchJar; not starting");
            } else {
                WatchCheck.start(Path.of(watchJar));
            }
        }
    }
}
