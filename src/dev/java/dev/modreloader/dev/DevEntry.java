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
            // Measured before the add: a post-construct failure leaves game-bus
            // listeners behind (no unregister), so the delta is the evidence.
            int listenersBefore = GlobalBusListeners.count();
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[ModReloader] autoAdd result: {}", result.message());
            if (verifyRollback) {
                RollbackCheck.start(jar, result, listenersBefore);
            } else if (verifyLan && result.ok()) {
                LanScreenCheck.start();
            }
        });
    }

    private static void installChecks() {
        if (Boolean.getBoolean("modreloader.verifyRelaunchPlan")) {
            RelaunchCheck.start();
        }
        String verifyJbrFlow = System.getProperty("modreloader.verifyJbrFlow", "").trim();
        if (!verifyJbrFlow.isEmpty()) {
            JbrPlayerFlowCheck.start(verifyJbrFlow);
        }
        String verifyRedefineProbe =
                System.getProperty("modreloader.verifyRedefineProbe", "").trim();
        if (!verifyRedefineProbe.isEmpty()) {
            RedefineProbeCheck.start(verifyRedefineProbe);
        }
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
        String verifyMpFei = System.getProperty("modreloader.verifyMpFei", "").trim();
        if (!verifyMpFei.isEmpty()) {
            MpFeiClientCheck.start(verifyMpFei);
        }
        String verifyPayload = System.getProperty("modreloader.verifyPayload", "").trim();
        if (!verifyPayload.isEmpty()) {
            PayloadCheck.start(Path.of(verifyPayload));
        }
        String verifyAddCommand = System.getProperty("modreloader.verifyAddCommand", "").trim();
        if (!verifyAddCommand.isEmpty()) {
            AddCommandCheck.start(Path.of(verifyAddCommand));
        }
        String verifyRunState = System.getProperty("modreloader.verifyRunState", "").trim();
        if (!verifyRunState.isEmpty()) {
            RunStateClientCheck.startClient(Path.of(verifyRunState));
        }
        String verifyAe2L2 = System.getProperty("modreloader.verifyAe2L2", "").trim();
        if (!verifyAe2L2.isEmpty()) {
            Ae2L2Check.start(verifyAe2L2);
        }
        String verifyCompat = System.getProperty("modreloader.verifyCompat", "").trim();
        if (!verifyCompat.isEmpty()) {
            CompatCheck.start(verifyCompat);
        }
        String verifyCreatePonder = System.getProperty("modreloader.verifyCreatePonder", "").trim();
        if (!verifyCreatePonder.isEmpty()) {
            CreatePonderCheck.start(Path.of(verifyCreatePonder));
        }
        String verifyPinyin = System.getProperty("modreloader.verifyPinyin", "").trim();
        if (!verifyPinyin.isEmpty()) {
            PinyinCheck.start(Path.of(verifyPinyin));
        }
        String verifyMixinFixture = System.getProperty("modreloader.verifyMixinFixture", "").trim();
        if (!verifyMixinFixture.isEmpty()) {
            MixinFixtureCheck.start(Path.of(verifyMixinFixture));
        }
        String verifyAtSpike = System.getProperty("modreloader.verifyAtSpike", "").trim();
        if (!verifyAtSpike.isEmpty()) {
            AtSpikeCheck.start(verifyAtSpike);
        }
        String verifyRegAudit = System.getProperty("modreloader.verifyRegAudit", "").trim();
        if (!verifyRegAudit.isEmpty()) {
            RegAuditCheck.start(verifyRegAudit);
        }
        String verifyCapabilities = System.getProperty("modreloader.verifyCapabilities", "").trim();
        if (!verifyCapabilities.isEmpty()) {
            CapabilityCheck.start(Path.of(verifyCapabilities));
        }
        String verifyCreativeAtTitle = System.getProperty("modreloader.verifyCreativeAtTitle", "").trim();
        if (!verifyCreativeAtTitle.isEmpty()) {
            CreativeTitleCheck.start(Path.of(verifyCreativeAtTitle));
        }
        String verifyMixinLayout = System.getProperty("modreloader.verifyMixinLayout", "").trim();
        if (!verifyMixinLayout.isEmpty()) {
            MixinLayoutCheck.start(verifyMixinLayout,
                    System.getProperty("modreloader.verifyMixinLayoutTarget",
                            "net.minecraft.client.gui.components.EditBox"));
        }
        if (Boolean.getBoolean("modreloader.verifyMpNotice")) {
            MpNoticeClientCheck.start();
        }
        String verifyMpRegistry = System.getProperty("modreloader.verifyMpRegistry", "").trim();
        if (!verifyMpRegistry.isEmpty()) {
            MpRegistryCheck.start(verifyMpRegistry);
        }
        String verifyMpLateJoin = System.getProperty("modreloader.verifyMpLateJoin", "").trim();
        if (!verifyMpLateJoin.isEmpty()) {
            MpLateJoinClientCheck.start(verifyMpLateJoin);
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
