package dev.modreloader.ui;

import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.inspect.ModInspection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.stream.Collectors;

/** Translation helpers shared by the watcher UI, commands and toasts. */
public final class ModReloaderI18n {
    private ModReloaderI18n() {
    }

    public static Component blockReason(ModInspection inspection) {
        if (!inspection.ok()) {
            String error = inspection.error() == null ? "" : inspection.error();
            if (error.contains("not a valid jar")) {
                return Component.translatable("modreloader.preflight.invalid_jar");
            }
            if (error.contains("not a NeoForge mod jar")) {
                return Component.translatable("modreloader.preflight.invalid_not_mod");
            }
            if (error.equals("not a file")) {
                return Component.translatable("modreloader.preflight.invalid_file");
            }
            if (error.equals("not a .jar")) {
                return Component.translatable("modreloader.preflight.invalid_extension");
            }
            if (error.contains("cannot identify mods")) {
                return Component.translatable("modreloader.preflight.invalid_metadata");
            }
            if (error.contains("jar declares no mods")) {
                return Component.translatable("modreloader.preflight.no_mods");
            }
            return Component.translatable("modreloader.preflight.invalid", error);
        }
        if (inspection.alreadyLoaded()) {
            return Component.translatable("modreloader.preflight.already_loaded");
        }
        if (inspection.tier() == ModInspection.Tier.T4) {
            return Component.translatable("modreloader.preflight.t4");
        }
        if (inspection.tier() == ModInspection.Tier.T5) {
            return Component.translatable("modreloader.preflight.t5");
        }
        for (ModInspection.Dependency dependency : inspection.dependencies()) {
            if (dependency.required() && !dependency.satisfied()) {
                return Component.translatable("modreloader.preflight.missing_dependency",
                        dependencyName(dependency.modId()), dependency.versionRange());
            }
        }
        if (inspection.tier() == ModInspection.Tier.T3 && !JbrSupport.available()) {
            return Component.translatable("modreloader.preflight.jbr");
        }
        if (inspection.mixinRuntimeMissing()) {
            return Component.translatable("modreloader.preflight.mixin_runtime",
                    MixinRuntimeSupport.name());
        }
        return Component.translatable("modreloader.preflight.can_load");
    }

    /**
     * FML and mod metadata speak modIds; users know product names. Only the one
     * dependency we ship is mapped — everything else stays untouched rather
     * than guessing.
     */
    public static String dependencyName(String modId) {
        if (MixinRuntimeSupport.MOD_ID.equals(modId)) {
            return MixinRuntimeSupport.name();
        }
        return modId;
    }

    public static Component summary(ModInspection inspection) {
        String ids = inspection.mods().stream()
                .map(mod -> mod.modId() + " " + mod.version())
                .collect(Collectors.joining(", "));
        if (ids.isEmpty()) {
            ids = inspection.fileName();
        }
        MutableComponent result = Component.literal(ids + " · " + inspection.tier().name());
        if (inspection.mixins()) {
            result.append(Component.literal(" · ")).append(Component.translatable("modreloader.preflight.mixins"));
        }
        if (inspection.registry()) {
            result.append(Component.literal(" · ")).append(Component.translatable("modreloader.preflight.registry"));
        }
        if (inspection.nativeCode()) {
            result.append(Component.literal(" · ")).append(Component.translatable("modreloader.preflight.native"));
        }
        return result;
    }
}
