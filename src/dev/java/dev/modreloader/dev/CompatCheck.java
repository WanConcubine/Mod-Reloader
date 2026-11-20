package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.inspect.ModInspector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.modreloader.reflect.UnsafeAccess;

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
    /** Ticks to keep running after the last jar ({@code -PverifyCompatSettle=}). */
    private static final int SETTLE_TICKS =
            Integer.getInteger("modreloader.verifyCompatSettle", 40);
    /**
     * {@code -PverifyShaderCount=<n>}: assert that the hot-added jar(s) put at
     * least n new entries into {@code GameRenderer}'s shader map. Shader replay
     * only runs after the hot-add resource reload, so this also proves the
     * ordering (the fixture's shader JSON is inside its own pack).
     */
    private static final int EXPECTED_SHADERS =
            Integer.getInteger("modreloader.verifyShaderCount", -1);
    /** {@code -PverifyCustomRegistry=<namespace:path>}: assert the hot-add created it. */
    private static final String CUSTOM_REGISTRY =
            System.getProperty("modreloader.verifyCustomRegistry", "").trim();
    private static final int CUSTOM_REGISTRY_SIZE =
            Integer.getInteger("modreloader.verifyCustomRegistrySize", 1);
    private static int shadersBefore = -1;
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
        if (totalTicks == TITLE_SETTLE_TICKS) {
            long handle = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
            int focused = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED);
            ModReloader.LOGGER.info("[CompatCheck] window focused={} (noFocus={})",
                    focused == GLFW.GLFW_TRUE, Boolean.getBoolean("modreloader.noFocus"));
        }
        if (totalTicks < TITLE_SETTLE_TICKS) {
            return;
        }
        if (index >= JARS.size()) {
            if (ticks >= SETTLE_TICKS) {
                verifyShaders();
                verifyCustomRegistry();
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
        if (shadersBefore < 0) {
            shadersBefore = shaderCount();
        }
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

    private static void verifyCustomRegistry() {
        if (CUSTOM_REGISTRY.isEmpty()) {
            return;
        }
        try {
            var id = net.minecraft.resources.ResourceLocation.parse(CUSTOM_REGISTRY);
            var registry = BuiltInRegistries.REGISTRY.get(id);
            int size = registry == null ? -1 : registry.size();
            boolean pass = registry != null && size >= CUSTOM_REGISTRY_SIZE;
            ModReloader.LOGGER.info(
                    "[CompatCheck] custom registry RESULT: {} ({} present={}, size={}, expected>={})",
                    pass ? "PASS" : "FAIL", id, registry != null, size, CUSTOM_REGISTRY_SIZE);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[CompatCheck] custom registry check failed", t);
            ModReloader.LOGGER.info("[CompatCheck] custom registry RESULT: FAIL ({})", CUSTOM_REGISTRY);
        }
    }

    private static void verifyShaders() {
        if (EXPECTED_SHADERS < 0) {
            return;
        }
        int after = shaderCount();
        int added = shadersBefore < 0 || after < 0 ? -1 : after - shadersBefore;
        boolean pass = added >= EXPECTED_SHADERS;
        ModReloader.LOGGER.info("[CompatCheck] shaders RESULT: {} ({} -> {}, expected +{})",
                pass ? "PASS" : "FAIL", shadersBefore, after, EXPECTED_SHADERS);
    }

    private static int shaderCount() {
        try {
            var renderer = net.minecraft.client.Minecraft.getInstance().gameRenderer;
            Object shaders = dev.modreloader.reflect.UnsafeAccess.get(
                    renderer, net.minecraft.client.renderer.GameRenderer.class, "shaders");
            return shaders instanceof java.util.Map<?, ?> map ? map.size() : -1;
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[CompatCheck] cannot read the shader map", t);
            return -1;
        }
    }

    private static void finish() {
        started = false;
        ModReloader.LOGGER.info("[CompatCheck] stopping client");
        net.minecraft.client.Minecraft.getInstance().stop();
    }
}
