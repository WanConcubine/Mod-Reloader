package dev.modreloader.i18n;

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
        return blockReason(inspection, false, false);
    }

    public static Component blockReason(ModInspection inspection, boolean allowRegistryContent) {
        return blockReason(inspection, allowRegistryContent, false);
    }

    /**
     * @param allowRegistryContent        true when registry content can be hot-added (experimental).
     * @param allowLateAccessTransformers true when the experimental late-AT path is enabled; an AT
     *                                    is otherwise a hard gate, so it must be reported before
     *                                    the tier-specific text. Without this branch the dialog
     *                                    claimed "experimental loadable" while the Load button was
     *                                    disabled (Mekanism).
     */
    public static Component blockReason(ModInspection inspection, boolean allowRegistryContent,
                                        boolean allowLateAccessTransformers) {
        if (!inspection.ok()) {
            String error = inspection.error() == null ? "" : inspection.error();
            if (error.contains("GAMELIBRARY")) {
                return Component.translatable("modreloader.preflight.library");
            }
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
        if (inspection.accessTransformers() && !allowLateAccessTransformers) {
            // Three states: capability on, JBR without the flag, or a stock JVM.
            // "Use JBR" on a JBR session was the confusing case.
            if (JbrSupport.available()) {
                return Component.translatable("modreloader.preflight.access_transformer_late_off");
            }
            return Component.translatable(JbrSupport.status() == JbrSupport.Status.JBR_NO_ENHANCED
                    ? "modreloader.preflight.access_transformer_jbr_no_flag"
                    : "modreloader.preflight.access_transformer");
        }
        if (inspection.tier() == ModInspection.Tier.GUIFEI && !allowRegistryContent) {
            return Component.translatable("modreloader.preflight.guifei");
        }
        if (inspection.tier() == ModInspection.Tier.HUANGHOU) {
            return Component.translatable("modreloader.preflight.huanghou");
        }
        for (ModInspection.Dependency dependency : inspection.dependencies()) {
            if (dependency.required() && !dependency.satisfied()) {
                return Component.translatable("modreloader.preflight.missing_dependency",
                        dependencyName(dependency.modId()), dependency.versionRange());
            }
        }
        boolean mixinTier = inspection.tier() == ModInspection.Tier.FEI
                || (inspection.tier() == ModInspection.Tier.GUIFEI && inspection.mixins());
        if (mixinTier && !MixinRuntimeSupport.available()) {
            return Component.translatable("modreloader.preflight.mixin_runtime",
                    MixinRuntimeSupport.name());
        }
        if (mixinTier && !JbrSupport.available()) {
            // Still loadable: only layout-changing targets on loaded classes need JBR.
            return Component.translatable(JbrSupport.status() == JbrSupport.Status.JBR_NO_ENHANCED
                    ? "modreloader.preflight.mixin_no_jbr_flag"
                    : "modreloader.preflight.mixin_no_jbr");
        }
        if (inspection.accessTransformers()) {
            // Reachable only when the add is otherwise allowed: the Load button is
            // usable because the experimental late-AT path is on. Saying so here is
            // the warning; anything earlier would hide the gate that really blocked.
            return Component.translatable("modreloader.preflight.access_transformer_late");
        }
        if (inspection.tier() == ModInspection.Tier.GUIFEI) {
            return Component.translatable("modreloader.preflight.guifei_loadable");
        }
        return Component.translatable("modreloader.preflight.can_load");
    }

    /**
     * FML and mod metadata speak modIds; users know product names. Only the one
     * dependency we ship is mapped — everything else stays untouched rather
     * than guessing.
     */
    public static Component tier(ModInspection.Tier tier) {
        return Component.translatable(tier.key());
    }

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
        MutableComponent result = Component.literal(ids + " · ").append(tier(inspection.tier()));
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
