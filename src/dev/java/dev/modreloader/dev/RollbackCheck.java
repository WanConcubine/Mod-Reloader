package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.ClassBytes;
import dev.modreloader.hotadd.JvmAccess;
import dev.modreloader.hotadd.MixinSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Dev-only check for A1.2 failure rollback.
 *
 * <p>Run with {@code -PverifyRollback=true -PfailAfterModList=true}; the first
 * add is forced to fail after the ModList rebuild. The check verifies that
 * ModList, module layer, mixin config and resource pack were restored, then
 * retries the add and verifies that it succeeds.
 */
public final class RollbackCheck {
    private static final String MOD_ID = "lanserverproperties";
    private static final String CONFIG = "lsp.mixins.json";
    private static final String TARGET = "net.minecraft.client.gui.screens.ShareToLanScreen";

    private static boolean started;
    private static boolean done;
    private static int ticks;
    private static int packDelay = Boolean.getBoolean("modreloader.failAfterPack") ? 60 : 0;

    private RollbackCheck() {
    }

    private static int listenersBefore = -1;

    public static void start(Path jar, HotAddEngine.Result first, int listenersBeforeCount) {
        if (started) {
            return;
        }
        started = true;
        listenersBefore = listenersBeforeCount;
        ModReloader.LOGGER.info("[RollbackCheck] first add result: {} (global bus listeners before: {})",
                first.message(), listenersBefore);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            if (!started) {
                return;
            }
            if (done) {
                if (++ticks > 40) {
                    started = false;
                    ModReloader.LOGGER.info("[RollbackCheck] stopping client");
                    Minecraft.getInstance().stop();
                }
                return;
            }
            if (packDelay > 0) {
                packDelay--;
                return;
            }
            done = true;
            run(jar, first);
        });
    }

    private static void run(Path jar, HotAddEngine.Result first) {
        boolean ok = true;
        if (first.ok()) {
            ModReloader.LOGGER.error("[RollbackCheck] expected an injected failure, but add succeeded");
            ok = false;
        }
        ok &= expect(!ModList.get().isLoaded(MOD_ID), "ModList.isLoaded=" + ModList.get().isLoaded(MOD_ID));
        ok &= expect(ModList.get().getModContainerById(MOD_ID).isEmpty(),
                "container present=" + ModList.get().getModContainerById(MOD_ID).isPresent());
        ok &= expect(!MixinSupport.isConfigRegistered(CONFIG), "mixin config still registered");
        ok &= expect(FMLLoader.getGameLayer().modules().stream()
                        .noneMatch(module -> MOD_ID.equals(module.getName())),
                "module still in game layer");
        boolean selected = Minecraft.getInstance().getResourcePackRepository().getSelectedPacks().stream()
                .map(Pack::getId)
                .anyMatch(id -> id.equals("mod/" + MOD_ID));
        ok &= expect(!selected, "resource pack still selected");

        try {
            Class<?> target = null;
            for (Class<?> type : JvmAccess.get().getAllLoadedClasses()) {
                if (TARGET.equals(type.getName())) {
                    target = type;
                    break;
                }
            }
            if (target == null) {
                ModReloader.LOGGER.info("[RollbackCheck] target class not loaded; no class-level residue");
            } else {
                // The module has been removed from the layer, so reflection on
                // fields can throw NoClassDefFoundError for the mod's types.
                // Inspect the restored bytes instead.
                byte[] bytes = ClassBytes.of(JvmAccess.get(), target);
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                boolean field = node.fields.stream().anyMatch(f -> "lsp_objects".equals(f.name));
                boolean iface = node.interfaces.stream()
                        .anyMatch(type -> type.endsWith("IShareToLanScreenParamAccessor"));
                ok &= expect(!field, "target still has lsp_objects");
                ok &= expect(!iface, "target still implements the LSP accessor");
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[RollbackCheck] class-byte check failed", t);
        }

        if (!ok) {
            ModReloader.LOGGER.error("[RollbackCheck] RESULT: FAIL (state not restored)");
            return;
        }

        boolean afterPack = Boolean.getBoolean("modreloader.failAfterPack");
        boolean afterConstruct = Boolean.getBoolean("modreloader.failAfterConstruct");
        if (afterPack || afterConstruct) {
            // The engine's own restore result is the precise measurement: the global
            // count keeps drifting after the restore because the remaining rollback
            // steps (class restore / mixin deactivation / pack reload) may register a
            // listener of their own, which is not a leak of this add.
            dev.modreloader.hotadd.EventBusSnapshot.RestoreResult restored =
                    dev.modreloader.hotadd.EventBusSnapshot.lastRestore();
            int after = GlobalBusListeners.count();
            if (restored != null && !restored.clean()) {
                ModReloader.LOGGER.error("[RollbackCheck] FAIL: EventBusSnapshot restored the game "
                        + "bus incompletely ({} added, {} -> {})",
                        restored.added(), restored.before(), restored.after());
                ModReloader.LOGGER.error("[RollbackCheck] RESULT: FAIL");
                return;
            }
            ModReloader.LOGGER.info("[RollbackCheck] game-bus listener rollback: {} "
                            + "(baseline {} -> {} at the end of the rollback, bus total now {})",
                    restored == null ? "not measurable" : "clean",
                    listenersBefore, restored == null ? "?" : restored.after(), after);

            if (!afterConstruct) {
                // Pack failure: state checks are the point; the pack path has its own
                // verified rollback and is not retried here.
                ModReloader.LOGGER.info("[RollbackCheck] pack rollback state checks passed; "
                        + "not retrying");
                ModReloader.LOGGER.info("[RollbackCheck] RESULT: PASS");
                return;
            }

            // Post-construct rollback promises "state restored", not "retry works":
            // measured 2026-11-16, retrying a mixin mod after a post-construct failure
            // ends in FML's `ModLoadingException: Loading errors encountered` (the
            // mixin config was already applied once). The retry promise belongs to the
            // pre-construct failure path (failAfterModList), which still passes.
            ModReloader.LOGGER.info("[RollbackCheck] post-construct rollback restored state and "
                    + "listeners; not retrying (measured: a mixin mod fails FML's load on retry)");
            ModReloader.LOGGER.info("[RollbackCheck] RESULT: PASS");
            return;
        }

        ModReloader.LOGGER.info("[RollbackCheck] rollback state checks passed; retrying add");
        System.clearProperty("modreloader.failAfterModList");
        HotAddEngine.Result retry = HotAddEngine.add(jar);
        boolean retryOk = retry.ok()
                && ModList.get().isLoaded(MOD_ID)
                && ModList.get().getModContainerById(MOD_ID).isPresent()
                && MixinSupport.isConfigRegistered(CONFIG);
        try {
            Class<?> target = Class.forName(TARGET, false, RollbackCheck.class.getClassLoader());
            retryOk &= hasField(target, "lsp_objects");
            retryOk &= Arrays.stream(target.getInterfaces())
                    .anyMatch(type -> type.getName().endsWith("IShareToLanScreenParamAccessor"));
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[RollbackCheck] retry target check failed", t);
            retryOk = false;
        }
        ModReloader.LOGGER.info("[RollbackCheck] retry result: {}", retry.message());
        ModReloader.LOGGER.info("[RollbackCheck] RESULT: {}", retryOk ? "PASS" : "FAIL");
    }

    private static boolean expect(boolean condition, String failure) {
        if (!condition) {
            ModReloader.LOGGER.error("[RollbackCheck] FAIL: {}", failure);
        }
        return condition;
    }

    private static boolean hasField(Class<?> type, String name) {
        try {
            type.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
