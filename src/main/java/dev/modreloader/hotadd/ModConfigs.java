package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads the configs a hot-added mod registered from its constructor.
 *
 * <p>{@code ModContainer#registerConfig} only records the spec in
 * {@link ConfigTracker}; the actual file/`ConfigValue` load happens in FML's
 * startup sequence. A hot-added mod therefore has a registered-but-unloaded
 * config, and the first read throws
 * {@code IllegalStateException: Cannot get config value before config is loaded}
 * — observed from Ex Nihilo's machine renderer/block entity (machine tick time
 * is a config value), which crashed the client while rendering the block entity.
 *
 * <p>There is no public per-mod load hook, so this mirrors what
 * {@code ConfigTracker.loadConfigs} does for the type, restricted to this
 * container's configs: {@code openConfig} then {@code loadConfig} with a
 * {@code ModConfigEvent.Loading}. Loading the whole type instead would re-read
 * every already-loaded mod's config and re-fire their reload events.
 *
 * <p>{@code SERVER} configs need the server's {@code serverconfig} directory
 * (private helper in {@code ServerLifecycleHooks}); they are skipped with a
 * warning — a hot-added mod that puts machine values in a SERVER config still
 * needs a restart.
 */
public final class ModConfigs {
    private static MethodHandle openConfig;

    private ModConfigs() {
    }

    public static void load(ModContainer container) {
        try {
            // configsByMod is an instance field on the ConfigTracker singleton.
            Map<String, List<ModConfig>> byMod = UnsafeAccess.get(
                    ConfigTracker.INSTANCE, ConfigTracker.class, "configsByMod");
            List<ModConfig> configs = byMod.get(container.getModId());
            if (configs == null || configs.isEmpty()) {
                return;
            }
            Path configDir = FMLPaths.CONFIGDIR.get();
            int loaded = 0;
            for (ModConfig config : configs) {
                if (config.getLoadedConfig() != null) {
                    continue;
                }
                if (config.getType() == ModConfig.Type.SERVER) {
                    ModReloader.LOGGER.warn(
                            "[ModReloader] {} has a SERVER config; hot-add cannot load it "
                                    + "(needs the world's serverconfig path). Restart to apply it.",
                            config.getFileName());
                    continue;
                }
                // openConfig() resolves <configDir>/<fileName>, loads it and posts
                // ModConfigEvent.Loading; calling loadConfig() again would re-read
                // (and on a missing/partially written file, try to recreate) it.
                handles();
                openConfig.invoke(config, configDir, null);
                loaded++;
            }
            ModReloader.LOGGER.info("[ModReloader] loaded {} config(s) for {}: {}",
                    loaded, container.getModId(),
                    configs.stream().map(ModConfig::getFileName).toList());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] config load failed for {}; "
                    + "mods reading config values will misbehave", container.getModId(), t);
        }
    }

    private static void handles() throws NoSuchMethodException, IllegalAccessException {
        if (openConfig != null) {
            return;
        }
        openConfig = UnsafeAccess.trusted().findStatic(ConfigTracker.class, "openConfig",
                MethodType.methodType(void.class, ModConfig.class, Path.class, Path.class));
    }
}
