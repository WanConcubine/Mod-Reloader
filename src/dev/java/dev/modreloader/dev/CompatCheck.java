package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.inspect.ModInspector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only compatibility-matrix harness: hot-add a list of real jars at the title
 * screen, one at a time, and log a one-line verdict per jar so the results can be
 * collected into a matrix (predicted tier vs. actual outcome).
 *
 * <p>{@code -PverifyCompat=<jar>[,<jar>...]} — dependencies must be listed first.
 * The title screen is used on purpose: it exercises module/mixin/ModList/construct/
 * registry/client-resource/client-registration paths without creating a world, so
 * the matrix is about the hot-add engine, not about gameplay.
 *
 * <p>This deliberately does <em>not</em> change behaviour based on the tier
 * prediction: a mod predicted HUANGHOU is still attempted, because the matrix is
 * exactly the evidence that the prediction is wrong in either direction.
 */
public final class CompatCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    /** Gap between two adds so the client resource reload of the previous one settles. */
    private static final int PER_JAR_TICKS = 60;
    private static final int TIMEOUT_TICKS = 20 * 600;

    private static final List<Path> JARS = new ArrayList<>();
    private static int ticks;
    private static int totalTicks;
    private static int index;
    private static boolean started;

    private CompatCheck() {
    }

    public static void start(String csv) {
        if (started) {
            return;
        }
        for (String part : csv.split(",")) {
            String raw = part.trim();
            if (!raw.isEmpty()) {
                JARS.add(Path.of(raw).toAbsolutePath().normalize());
            }
        }
        if (JARS.isEmpty()) {
            ModReloader.LOGGER.warn("[CompatCheck] no jars given; not starting");
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[CompatCheck] queue: {}", JARS);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[CompatCheck] TIMEOUT after {} at jar {}/{}",
                    TIMEOUT_TICKS / 20, index, JARS.size());
            finish();
            return;
        }
        if (totalTicks < TITLE_SETTLE_TICKS) {
            return;
        }
        if (index >= JARS.size()) {
            if (ticks >= 40) {
                ModReloader.LOGGER.info("[CompatCheck] DONE {}/{}", index, JARS.size());
                finish();
            }
            return;
        }
        if (ticks < PER_JAR_TICKS) {
            return;
        }
        ticks = 0;
        add(JARS.get(index));
    }

    private static void add(Path jar) {
        int blocksBefore = BuiltInRegistries.BLOCK.size();
        int itemsBefore = BuiltInRegistries.ITEM.size();
        String tier = "?";
        try {
            ModInspection inspection = ModInspector.inspect(jar);
            tier = inspection.ok() ? inspection.tier().name() : "INSPECT_FAILED";
        } catch (Throwable t) {
            tier = "INSPECT_THREW";
        }
        ModReloader.LOGGER.info("[CompatCheck] START {} predicted={} blocks={} items={}",
                jar.getFileName(), tier, blocksBefore, itemsBefore);
        HotAddEngine.Result result;
        try {
            result = HotAddEngine.add(jar);
        } catch (Throwable t) {
            ModReloader.LOGGER.info("[CompatCheck] RESULT {} predicted={} ok=false stage=THREW kind={} msg={}",
                    jar.getFileName(), tier, t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
            index++;
            return;
        }
        String loadedId = "";
        try {
            // The jar's first mod id, for the isLoaded probe.
            ModInspection inspection = ModInspector.inspect(jar);
            if (inspection.ok() && !inspection.mods().isEmpty()) {
                loadedId = inspection.mods().get(0).modId();
            }
        } catch (Throwable ignored) {
        }
        boolean isLoaded = !loadedId.isEmpty() && ModList.get().isLoaded(loadedId);
        ModReloader.LOGGER.info(
                "[CompatCheck] RESULT {} predicted={} ok={} stage={} kind={} isLoaded={} "
                        + "blocks {}->{} items {}->{} msg={}",
                jar.getFileName(), tier, result.ok(), result.stage(), result.kind(), isLoaded,
                blocksBefore, BuiltInRegistries.BLOCK.size(), itemsBefore,
                BuiltInRegistries.ITEM.size(), result.message());
        index++;
    }

    private static void finish() {
        started = false;
        ModReloader.LOGGER.info("[CompatCheck] stopping client");
        net.minecraft.client.Minecraft.getInstance().stop();
    }
}
