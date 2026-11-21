package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import dev.modreloader.reflect.UnsafeAccess;
import dev.modreloader.inspect.MixinRuntimeApi;
import dev.modreloader.reflect.JvmAccess;
import dev.modreloader.reflect.LaunchPluginMonitor;

/**
 * Runtime Mixin plumbing for hot-added mods.
 *
 * <p>Mixin normally freezes its config list after startup. This class reopens it
 * through MixinRuntime (dev-only dependency), registers the configs declared by
 * the new mod, and applies the new mixins to every target:
 *
 * <ul>
 *   <li>targets that are already loaded are transformed and handed to JBR's
 *       enhanced {@code redefineClasses};</li>
 *   <li>targets that are not loaded are loaded immediately with
 *       {@code initialize=false}, so Mixin applies on that first load. We do not
 *       transform them again: re-applying a config to an already-mixed class can
 *       strip the interface merge.</li>
 * </ul>
 *
 * <p>Every mutation registers an undo action in {@link Rollback}. Loaded target
 * bytes are captured before the new config is attached to the transformer, so a
 * failure can attempt to redefine the class back. JBR's ability to remove
 * interfaces is JVM-dependent; rollback reports failures instead of hiding them.
 *
 * <p>JBR's redefine also runs Mixin's agent transformer, which would apply the
 * config a second time to bytes we already mixed (MixinExtras WrapOperation then
 * fails and surfaces as {@code ClassFormatError}). {@link MixinAgentGuard}
 * suspends that transformer only for our targets during the redefine window.
 * While transforming, only the configs added by this call are active: otherwise
 * a later hot-add re-applies earlier mods' mixins to the new target bytes.
 *
 * <p>Accessor/invoker interfaces are mixin classes that Mixin rewrites when the
 * interface itself is defined (static {@code @Invoker} methods get a call into
 * the target's generated accessor). That only happens when the config was
 * prepared with Mixin's coprocessors attached as listeners - see
 * {@link #attachConfigListeners}. Interfaces loaded before this add are
 * transformed and redefined by {@link #processLoadedAccessors}.
 *
 * <p>Limits: this re-applies every registered config to loaded target bytes. It
 * is only safe when the target has not been changed by another agent, and it
 * needs JBR ({@code -XX:+AllowEnhancedClassRedefinition}) for layout changes.
 */
public final class MixinSupport {
    public record Report(List<String> configs, List<String> registered, List<String> skipped,
                         List<String> targets, List<String> redefined, List<String> eager,
                         List<String> deferred, List<String> errors, List<String> warnings) {
        public static Report empty() {
            return new Report(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of());
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private record Applied(List<String> redefined, List<String> eager, List<String> deferred) {
    }

    private record LoadedConfig(String name, IMixinConfig config, boolean prepared) {
    }

    private record RemovedConfig(IMixinConfig config, Config handle, boolean prepared) {
    }

    private static final Map<String, RemovedConfig> REMOVED_CONFIGS = new HashMap<>();
    private static final Set<String> LISTENED_CONFIGS = ConcurrentHashMap.newKeySet();

    private MixinSupport() {
    }

    public static Report register(List<ModFileParser.MixinConfig> configs,
                                  Set<String> hotModIds, String primaryModId, Rollback rollback) {
        List<String> configNames = new ArrayList<>();
        List<String> registered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> addedConfigs = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();

        List<String> apiProblems = MixinRuntimeApi.probe();
        if (!apiProblems.isEmpty()) {
            errors.add("MixinRuntime (mr) is unusable: " + apiProblems);
            return new Report(configNames, registered, skipped, List.of(), List.of(), List.of(),
                    List.of(), errors, warnings);
        }

        // Snapshot Mixin's accessor registry before preparing: preparing a config
        // is what registers accessor/invoker interfaces with the coprocessor that
        // rewrites them on load, so the difference is what this add introduced.
        Object processor = null;
        Map<String, Object> accessorRegistry = null;
        Set<String> accessorsBefore = Set.of();
        try {
            processor = MixinRuntimeApi.getProcessor(MixinRuntimeApi.getTransformer());
            accessorRegistry = accessorRegistry(processor);
            if (accessorRegistry != null) {
                accessorsBefore = new HashSet<>(accessorRegistry.keySet());
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot read Mixin's accessor registry before preparing configs", t);
        }

        List<LoadedConfig> loadedConfigs = new ArrayList<>();
        for (ModFileParser.MixinConfig config : configs) {
            configNames.add(config.config());
            if (!requirementsMet(config.requiredMods(), hotModIds)) {
                skipped.add(config.config() + " (missing " + config.requiredMods() + ")");
                continue;
            }
            String modId = config.requiredMods().isEmpty() ? primaryModId : config.requiredMods().get(0);
            addedConfigs.add(config.config());
            try {
                loadedConfigs.add(loadConfig(config.config(), modId));
            } catch (Throwable t) {
                errors.add("register " + config.config() + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                ModReloader.LOGGER.error("[ModReloader] mixin config registration failed: {}",
                        config.config(), t);
            }
        }

        if (errors.isEmpty()) {
            for (LoadedConfig loaded : loadedConfigs) {
                try {
                    if (!loaded.prepared()) {
                        prepareConfig(loaded.name(), loaded.config());
                    }
                    registered.add(loaded.name());
                    targets.addAll(loaded.config().getTargets());
                } catch (Throwable t) {
                    errors.add("prepare " + loaded.name() + ": " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                    ModReloader.LOGGER.error("[ModReloader] mixin config prepare failed: {}",
                            loaded.name(), t);
                }
            }
        }

        Applied applied = new Applied(List.of(), List.of(), List.of());
        Map<String, Class<?>> loadedNow = Map.of();
        if (errors.isEmpty() && !registered.isEmpty()) {
            loadedNow = loadedClasses();
            Map<String, byte[]> originals = captureOriginals(targets, loadedNow);
            IMixinTransformer transformer = MixinRuntimeApi.getTransformer();
            if (processor == null) {
                processor = MixinRuntimeApi.getProcessor(transformer);
            }
            for (LoadedConfig loaded : loadedConfigs) {
                if (!registered.contains(loaded.name())) {
                    continue;
                }
                try {
                    MixinRuntimeApi.addIntoProcessor(processor, loaded.config());
                    raiseCompatibilityLevel(loaded.config(), loaded.name());
                } catch (Throwable t) {
                    errors.add("activate " + loaded.name() + ": " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                    ModReloader.LOGGER.error("[ModReloader] cannot activate mixin config: {}",
                            loaded.name(), t);
                }
            }
            if (errors.isEmpty()) {
                // transformClassBytes applies every active config. For a second
                // hot-add that would re-apply earlier mods' mixins to the new
                // target bytes; isolate the new configs while we transform.
                List<Object> allActive = MixinRuntimeApi.getConfigs(processor);
                List<Object> allPending = MixinRuntimeApi.getPendingConfigs(processor);
                Set<String> newNames = new LinkedHashSet<>(registered);
                try {
                    MixinRuntimeApi.setConfigs(processor, filterByNames(allActive, newNames));
                    MixinRuntimeApi.setPendingConfigs(processor, filterByNames(allPending, newNames));
                    applied = applyTargets(targets, loadedNow, originals, rollback, errors,
                            warnings, processor, allActive, allPending, newNames);
                } finally {
                    MixinRuntimeApi.setConfigs(processor, allActive);
                    MixinRuntimeApi.setPendingConfigs(processor, allPending);
                }
            }
        }

        if (errors.isEmpty() && accessorRegistry != null) {
            List<String> accessors = new ArrayList<>();
            for (String name : accessorRegistry.keySet()) {
                if (!accessorsBefore.contains(name)) {
                    accessors.add(name);
                }
            }
            if (!accessors.isEmpty()) {
                ModReloader.LOGGER.info(
                        "[ModReloader] accessor/invoker interface mixin(s), rewritten when the "
                                + "interface class is defined: {}", accessors);
                // Registered mixin classes already loaded by an earlier add keep
                // Mixin's stub forever; the coprocessor only fires on definition.
                processLoadedAccessors(accessors, loadedNow, rollback, errors, warnings);
            }
        }

        // Rollback is LIFO, and class restores were registered inside applyTargets.
        // Queue config removal last so it runs first: restoring class bytes while a
        // config is still active lets Mixin's agent re-apply the injection, and the
        // rollback then leaves a handler whose target classes are about to be
        // unloaded (measured with ModernFix: next tick NoClassDefFoundError).
        for (String name : addedConfigs) {
            rollback.add("remove mixin config " + name, () -> removeConfig(name));
        }

        return new Report(List.copyOf(configNames), List.copyOf(registered), List.copyOf(skipped),
                List.copyOf(targets), applied.redefined(), applied.eager(), applied.deferred(),
                List.copyOf(errors), List.copyOf(warnings));
    }

    public static boolean isConfigRegistered(String configName) {
        try {
            IMixinTransformer transformer = MixinRuntimeApi.getTransformer();
            Object processor = MixinRuntimeApi.getProcessor(transformer);
            List<Object> active = MixinRuntimeApi.getConfigs(processor);
            if (active != null) {
                for (Object candidate : active) {
                    if (candidate instanceof IMixinConfig config && configName.equals(config.getName())) {
                        return true;
                    }
                }
            }
            List<Object> pending = MixinRuntimeApi.getPendingConfigs(processor);
            if (pending != null) {
                for (Object candidate : pending) {
                    if (candidate instanceof IMixinConfig config && configName.equals(config.getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot inspect mixin config state", t);
        }
        return false;
    }

    private static List<Object> filterByNames(List<Object> configs, Set<String> names) {
        if (configs == null) {
            return List.of();
        }
        List<Object> filtered = new ArrayList<>();
        for (Object candidate : configs) {
            if (candidate instanceof IMixinConfig config && names.contains(config.getName())) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private static boolean requirementsMet(List<String> requiredMods, Set<String> hotModIds) {
        for (String id : requiredMods) {
            if (hotModIds.contains(id)) {
                continue;
            }
            if (ModList.get() != null && ModList.get().isLoaded(id)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static LoadedConfig loadConfig(String configName, String modId) throws Throwable {
        RemovedConfig removed = REMOVED_CONFIGS.remove(configName);
        if (removed != null) {
            if (removed.handle() != null) {
                try {
                    Mixins.getConfigs().add(removed.handle());
                } catch (Throwable t) {
                    ModReloader.LOGGER.warn("[ModReloader] cannot re-add mixin config handle {}", configName, t);
                }
            }
            ModReloader.LOGGER.info("[ModReloader] reusing prepared mixin config {}", configName);
            return new LoadedConfig(configName, removed.config(), removed.prepared());
        }

        Class<?> deferred = Class.forName("net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration");
        UnsafeAccess.setStaticBoolean(deferred, "added", false);

        Mixins.addConfiguration(configName);
        IMixinConfig config = findConfig(configName);
        ModReloader.LOGGER.info("[ModReloader] mixin config {}: side={} platform={}",
                configName, MixinEnvironment.getDefaultEnvironment().getSide(),
                dev.modreloader.Platform.isClient() ? "client" : "server");
        raiseCompatibilityLevel(config, configName);
        if (!config.hasDecoration(FabricUtil.KEY_MOD_ID)) {
            config.decorate(FabricUtil.KEY_MOD_ID, modId);
        }
        MixinRuntimeApi.onSelect(config);
        return new LoadedConfig(configName, config, false);
    }

    private static void prepareConfig(String configName, IMixinConfig config) throws Throwable {
        try {
            UnsafeAccess.setBoolean(config, config.getClass(), "prepared", false);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot reset MixinConfig.prepared for {}", configName, t);
        }

        attachConfigListeners(config);
        IMixinTransformer transformer = MixinRuntimeApi.getTransformer();
        MixinRuntimeApi.prepare(config, transformer);
        postInitialise(config, transformer);
        raiseCompatibilityLevel(config, configName);
    }

    /**
     * {@code MixinProcessor.prepareConfigs} registers every coprocessor and the
     * inheritance tracker as listeners on each config before preparing it. The
     * accessor coprocessor is the only one that matters for hot-add: it records
     * each accessor/invoker interface at {@code onPrepare} and rewrites that
     * interface's static methods when the interface class is defined. Preparing a
     * config by hand skips this, so ponder's {@code RenderTypeAccessor} kept
     * Mixin's "Mixin application failed!" stub and the first render in a hot-added
     * Create crashed (issue 65).
     */
    private static void attachConfigListeners(IMixinConfig config) {
        String name = config.getName();
        if (!LISTENED_CONFIGS.add(name)) {
            return;
        }
        try {
            Object processor = MixinRuntimeApi.getProcessor(MixinRuntimeApi.getTransformer());
            Object coprocessors = UnsafeAccess.get(processor, processor.getClass(), "coprocessors");
            Class<?> listenerType = Class.forName(
                    "org.spongepowered.asm.mixin.transformer.MixinConfig$IListener");
            MethodHandle addListener = UnsafeAccess.trusted().findVirtual(
                    Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig"),
                    "addListener", MethodType.methodType(void.class, listenerType));
            int attached = 0;
            if (coprocessors instanceof Iterable<?> iterable) {
                for (Object coprocessor : iterable) {
                    if (listenerType.isInstance(coprocessor)) {
                        addListener.invoke(config, coprocessor);
                        attached++;
                    }
                }
            }
            Object tracker = UnsafeAccess.getStatic(Class.forName(
                    "org.spongepowered.asm.mixin.transformer.MixinInheritanceTracker"), "INSTANCE");
            addListener.invoke(config, tracker);
            ModReloader.LOGGER.info("[ModReloader] mixin config {}: attached {} processor listener(s) "
                    + "for on-load interface rewriting", name, attached);
        } catch (Throwable t) {
            LISTENED_CONFIGS.remove(name);
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot attach Mixin processor listeners to {}", name, t);
        }
    }

    /** Mixin's live accessor-coprocessor registry ({@code class name -> MixinInfo}). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> accessorRegistry(Object processor) {
        try {
            Object coprocessors = UnsafeAccess.get(processor, processor.getClass(), "coprocessors");
            if (coprocessors instanceof Iterable<?> iterable) {
                for (Object coprocessor : iterable) {
                    try {
                        Object registry = UnsafeAccess.get(coprocessor, coprocessor.getClass(),
                                "accessorMixins");
                        if (registry instanceof Map<?, ?> map) {
                            return (Map<String, Object>) map;
                        }
                    } catch (IllegalStateException notTheAccessorCoprocessor) {
                        // another coprocessor; keep looking
                    }
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot inspect Mixin's accessor registry", t);
        }
        return null;
    }

    /**
     * A class name registered with the accessor coprocessor is rewritten by Mixin
     * when it is <em>defined</em>. If an earlier add already loaded it, rewrite and
     * redefine it here instead, or its static {@code @Invoker} methods stay stubs.
     */
    private static void processLoadedAccessors(List<String> accessors, Map<String, Class<?>> loaded,
                                               Rollback rollback, List<String> errors,
                                               List<String> warnings) {
        Instrumentation instrumentation;
        try {
            instrumentation = JvmAccess.get();
        } catch (Throwable t) {
            errors.add("cannot obtain Instrumentation: " + t);
            return;
        }
        for (String dotted : accessors) {
            Class<?> type = loaded.get(dotted);
            if (type == null) {
                continue;
            }
            byte[] original = ClassBytesCache.get(dotted);
            if (original == null) {
                original = readClassResource(dotted);
            }
            if (original == null) {
                warnings.add("accessor interface " + dotted + " is already loaded and its bytes "
                        + "cannot be read; its static @Invoker methods stay stubs until restart");
                continue;
            }
            original = transplantLiveAccess(type, original, dotted);
            byte[] processed;
            try {
                processed = transformBytes(original, dotted);
            } catch (Throwable t) {
                errors.add("transform accessor interface " + dotted + ": " + describe(t));
                continue;
            }
            if (processed == null) {
                errors.add("mixin transform returned null for accessor interface: " + dotted);
                continue;
            }
            if (java.util.Arrays.equals(processed, original)) {
                warnings.add("accessor interface " + dotted + " is already loaded and Mixin did not "
                        + "rewrite it; its static @Invoker methods stay stubs until restart");
                continue;
            }
            if (!MixinAgentGuard.safeToRedefine()) {
                errors.add("refusing to redefine accessor interface " + dotted + ": "
                        + MixinAgentGuard.detail());
                continue;
            }
            byte[] before = original;
            rollback.add("restore class " + dotted,
                    () -> redefineBack(instrumentation, type, before, dotted));
            List<String> redefined = new ArrayList<>();
            redefine(instrumentation, type, processed, original, dotted, redefined, errors);
            if (!redefined.isEmpty()) {
                ModReloader.LOGGER.info(
                        "[ModReloader] redefined already-loaded accessor interface {}", dotted);
            }
        }
    }

    /**
     * MixinProcessor normally calls this after {@code prepare}. Skipping it
     * leaves MixinInfo unvalidated: fields/methods still apply, but interface
     * merging and full validation do not. That is why A1.1 looked successful
     * while the LAN screen cast to the mod's accessor interface failed.
     */
    private static void postInitialise(IMixinConfig config, IMixinTransformer transformer) throws Throwable {
        Class<?> extensionsType = Class.forName("org.spongepowered.asm.mixin.transformer.ext.Extensions");
        UnsafeAccess.trusted()
                .findVirtual(config.getClass(), "postInitialise",
                        java.lang.invoke.MethodType.methodType(void.class, extensionsType))
                .invoke(config, transformer.getExtensions());
    }

    /**
     * A config's declared {@code compatibilityLevel} (e.g. JAVA_17) lowers
     * Mixin's global level, so Java 21 mixin classes are treated as newer than
     * supported. NeoForge starts at JAVA_21; restore that after every added
     * config.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void raiseCompatibilityLevel(IMixinConfig config, String label) {
        try {
            Class<?> levelType = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$CompatibilityLevel");
            Object java21 = Enum.valueOf((Class<? extends Enum>) levelType, "JAVA_21");
            Object current = MixinEnvironment.getCompatibilityLevel();
            boolean changed = false;
            if (!java21.equals(current)) {
                UnsafeAccess.setStatic(MixinEnvironment.class, "compatibility", java21);
                changed = true;
            }
            if (config != null) {
                Object configLevel = UnsafeAccess.get(config, config.getClass(), "compatibilityLevel");
                if (!java21.equals(configLevel)) {
                    UnsafeAccess.set(config, config.getClass(), "compatibilityLevel", java21);
                    changed = true;
                }
            }
            if (changed) {
                ModReloader.LOGGER.info(
                        "[ModReloader] Mixin compatibility level set to JAVA_21 after {} (global was {})",
                        label, current);
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot raise Mixin compatibility level after {}",
                    label, t);
        }
    }

    private static Applied applyTargets(Set<String> targets, Map<String, Class<?>> loaded,
                                        Map<String, byte[]> originals, Rollback rollback,
                                        List<String> errors, List<String> warnings, Object processor,
                                        List<Object> allActive, List<Object> allPending,
                                        Set<String> newNames) {
        List<String> redefined = new ArrayList<>();
        List<String> eager = new ArrayList<>();
        List<String> deferred = new ArrayList<>();

        Instrumentation instrumentation;
        try {
            instrumentation = JvmAccess.get();
        } catch (Throwable t) {
            errors.add("cannot obtain Instrumentation: " + t);
            return new Applied(redefined, eager, deferred);
        }

        // Pass 1: transform the already-loaded targets and refuse the whole batch
        // if any of them would change its superclass/interface list.
        //
        // JBR's enhanced redefinition can add fields and methods, but it cannot
        // change a loaded class's superclass or interfaces - `redefineClasses`
        // answers with `attempted to change superclass or interfaces`. That is
        // exactly what an accessor/interface mixin does (e.g. Searchables'
        // `AccessEditBox`, measured 2026-11-16), so applying it would fail after
        // the mixin configs were already activated. Detect it here, while nothing
        // has been mutated, and report it as its own failure instead of a
        // mysterious redefine error.
        Map<String, byte[]> mixedBytes = new HashMap<>();
        for (String target : targets) {
            String dotted = target.replace('/', '.');
            Class<?> type = loaded.get(dotted);
            if (type == null) {
                continue;
            }
            byte[] original = originals.get(dotted);
            if (original == null) {
                errors.add("cannot read the original bytes of " + dotted
                        + "; refusing rather than redefining blind (restart to load this mod)");
                continue;
            }
            byte[] mixed;
            try {
                mixed = transformBytes(original, dotted);
            } catch (Throwable t) {
                byte[] recomposed = recomposeTarget(dotted, original, processor,
                        allActive, allPending, newNames);
                if (recomposed != null) {
                    mixed = recomposed;
                    ModReloader.LOGGER.info(
                            "[ModReloader] {} was mixed by an earlier add (or late AT); recomposed "
                                    + "it from pre-mixin bytes with all active configs", dotted);
                } else {
                    errors.add("transform " + dotted + ": " + describe(t));
                    ModReloader.LOGGER.error(
                            "[ModReloader] mixin transform failed for {}", dotted, t);
                    continue;
                }
            }
            if (mixed == null) {
                errors.add("mixin transform returned null: " + dotted);
                continue;
            }
            String layout = layoutChange(original, mixed);
            if (layout != null) {
                if (!dev.modreloader.inspect.JbrSupport.available()) {
                    // Measured: JBR's enhanced redefinition does accept an added
                    // interface (LSP redefines AbstractWidget with one), so this is
                    // not a layout rule - it is the plain "no enhanced redefinition"
                    // case, which cannot be undone once the config is active.
                    errors.add("layout: " + dotted + " " + layout
                            + " (target is already loaded and this JVM cannot do enhanced "
                            + "redefinition; " + dev.modreloader.inspect.JbrSupport.describe()
                            + " - restart the game to load this mod)."
                            + dev.modreloader.inspect.JbrSupport.hint());
                    continue;
                }
                ModReloader.LOGGER.warn(
                        "[ModReloader] mixins change the layout of the already-loaded {}: {}; "
                                + "attempting it because enhanced redefinition is available",
                        dotted, layout);
                if (layout.contains("adds field(s)")) {
                    // New fields exist in the class, but instances created before the
                    // redefine never run the mixed constructor: they keep the JVM
                    // defaults (0/null/false) instead of the field initializers.
                    // Measured with Jade's FontMixin: glint read as 0f instead of NaN
                    // and the "no glint" check passed, dimming all text to ~65%.
                    String singleton = liveSingleton(type);
                    if (singleton != null) {
                        // Sodium's MinecraftMixin adds `fences` and reads it every
                        // tick: a live singleton at JVM defaults is not "wrong
                        // values" but an immediate NPE. The add still succeeds (the
                        // class layout is accepted), so this is recorded as J2.
                        String warning = dotted + " gains instance field(s) while a live "
                                + "instance exists (static " + singleton + "); reads can crash "
                                + "until restart (J2)";
                        warnings.add(warning);
                        ModReloader.LOGGER.warn("[ModReloader] {}", warning);
                    } else {
                        ModReloader.LOGGER.warn(
                                "[ModReloader] {} gains instance field(s) while already loaded; "
                                        + "existing instances keep JVM defaults instead of the "
                                        + "constructor values, so features reading them can be wrong "
                                        + "until the game restarts",
                                dotted);
                    }
                }
            }
            mixedBytes.put(dotted, mixed);
        }
        if (!errors.isEmpty()) {
            // Nothing was redefined: the caller rolls the mixin configs back and the
            // client keeps working.
            return new Applied(redefined, eager, deferred);
        }

        // From here on the mixin is live on loaded classes while the mod itself is
        // not constructed yet. At the title screen nothing ticks; in a running world
        // the server thread would execute injected handlers against uninitialised
        // static state (AE2's ChunkMap -> AEConfig.instance() null, issue 68). The
        // engine releases the barrier after common setup.
        ServerTickBarrier.hold("in-world add: mixins through common setup");

        Set<String> dottedTargets = new HashSet<>();
        for (String target : targets) {
            dottedTargets.add(target.replace('/', '.'));
        }
        // JBR's redefine invokes the Mixin agent, which would transform the bytes
        // we already mixed a second time. Keep it away from these targets only.
        try (MixinAgentGuard ignored = MixinAgentGuard.suspend(instrumentation, dottedTargets)) {
            for (String target : targets) {
                String dotted = target.replace('/', '.');
                Class<?> type = loaded.get(dotted);
                if (type != null) {
                    if (!MixinAgentGuard.safeToRedefine()) {
                        errors.add("refusing to redefine " + dotted + ": "
                                + MixinAgentGuard.detail()
                                + ". Redefining already-mixed bytes without the guard would "
                                + "transform them twice (issue 40); restart with the jar in mods/");
                        continue;
                    }
                    rollback.add("restore class " + dotted,
                            () -> redefineBack(instrumentation, type, originals.get(dotted), dotted));
                    redefine(instrumentation, type, mixedBytes.get(dotted), originals.get(dotted),
                            dotted, redefined, errors);
                    continue;
                }
                // Not loaded before registration. Load it now (without initializing)
                // so Mixin transforms it on its first definition; do not redefine.
                try {
                    Class<?> loadedType = Class.forName(dotted, false,
                            MixinSupport.class.getClassLoader());
                    rollback.add("restore class " + dotted,
                            () -> redefineBack(instrumentation, loadedType, originals.get(dotted),
                                    dotted));
                    eager.add(dotted);
                    ModReloader.LOGGER.info(
                            "[ModReloader] eagerly loaded mixin target (mixed on load): {}", dotted);
                } catch (Throwable t) {
                    deferred.add(dotted);
                    ModReloader.LOGGER.warn("[ModReloader] cannot eagerly load mixin target {}: {}",
                            dotted, t.toString());
                }
            }
        }
        return new Applied(List.copyOf(redefined), List.copyOf(eager), List.copyOf(deferred));
    }

    /**
     * Applies the active mixin configs to class bytes <em>without</em> touching the
     * live class.
     *
     * <p>This is Mixin's own byte-level entry point. MixinRuntime's
     * {@code transform(Class)} must not be used here: it retransforms the class to
     * read it (which applies prepared configs as a side effect) and then redefines
     * it, so a refused layout change would still land on the class.
     */
    private static byte[] transformBytes(byte[] original, String dotted) {
        IMixinTransformer transformer = MixinRuntimeApi.getTransformer();
        Object monitor = LaunchPluginMonitor.get();
        if (monitor == null) {
            return transformer.transformClassBytes(dotted, dotted, original);
        }
        // Normal class loading takes this monitor before Mixin's transformer lock;
        // a direct transform must do the same or a class load inside Mixin can
        // deadlock against a worldgen/server thread (issue 54).
        synchronized (monitor) {
            return transformer.transformClassBytes(dotted, dotted, original);
        }
    }

    /**
     * A target that an earlier add (or a late AT) already rewrote may no longer
     * contain the call sites a new config expects: two {@code @WrapOperation}s on
     * the same call leave only the first wrapper behind, so the second transform
     * scans 0 targets. Recomputing from the class's pre-mixin resource with every
     * active config reproduces the startup shape, where both wrappers compose;
     * the live access flags (load-time ATs, earlier rewrites) are copied back.
     *
     * @return the composite bytes, or {@code null} when this target was not
     *         rewritten before or the recomposition itself failed
     */
    private static byte[] recomposeTarget(String dotted, byte[] live, Object processor,
                                          List<Object> allActive, List<Object> allPending,
                                          Set<String> newNames) {
        if (ClassBytesCache.get(dotted) == null) {
            return null;
        }
        byte[] pristine = readClassResource(dotted);
        if (pristine == null) {
            return null;
        }
        try {
            MixinRuntimeApi.setConfigs(processor, allActive);
            MixinRuntimeApi.setPendingConfigs(processor, allPending);
            byte[] composite = transformBytes(pristine, dotted);
            return composite == null ? null : transplantAccess(live, composite);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] recomposing {} from pre-mixin bytes failed", dotted, t);
            return null;
        } finally {
            MixinRuntimeApi.setConfigs(processor, filterByNames(allActive, newNames));
            MixinRuntimeApi.setPendingConfigs(processor, filterByNames(allPending, newNames));
        }
    }

    /** Copies class/field/method access flags from the live class onto the composite. */
    private static byte[] transplantAccess(byte[] live, byte[] composite) {
        ClassNode liveNode = new ClassNode();
        new ClassReader(live).accept(liveNode, 0);
        ClassReader compositeReader = new ClassReader(composite);
        ClassNode compositeNode = new ClassNode();
        compositeReader.accept(compositeNode, 0);
        compositeNode.access = liveNode.access;

        Map<String, Integer> fieldAccess = new HashMap<>();
        for (FieldNode field : liveNode.fields) {
            fieldAccess.put(field.name + field.desc, field.access);
        }
        for (FieldNode field : compositeNode.fields) {
            Integer access = fieldAccess.get(field.name + field.desc);
            if (access != null) {
                field.access = access;
            }
        }
        Map<String, Integer> methodAccess = new HashMap<>();
        for (MethodNode method : liveNode.methods) {
            methodAccess.put(method.name + method.desc, method.access);
        }
        for (MethodNode method : compositeNode.methods) {
            Integer access = methodAccess.get(method.name + method.desc);
            if (access != null) {
                method.access = access;
            }
        }
        ClassWriter writer = new ClassWriter(compositeReader, 0);
        compositeNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Copies visibility and {@code final} from the live class onto bytes read from a
     * class resource.
     *
     * <p>ModLauncher applies access transformers while <em>defining</em> a class, so
     * the resource is the pre-AT shape. Redefining a hot-add target from it silently
     * dropped widening such as NeoForge's
     * {@code public UseOnContext.<init>}: the next {@code BlockStateBase.useItemOn}
     * threw {@code IllegalAccessError} (issue 67). Only the visibility/final bits are
     * taken from the live class; flags the transformer itself needs (synthetic,
     * bridge, varargs, enum, ...) stay exactly as the bytecode has them.
     */
    private static byte[] transplantLiveAccess(Class<?> live, byte[] bytes, String dotted) {
        try {
            ClassNode node = new ClassNode();
            ClassReader reader = new ClassReader(bytes);
            reader.accept(node, 0);
            int mask = org.objectweb.asm.Opcodes.ACC_PUBLIC
                    | org.objectweb.asm.Opcodes.ACC_PROTECTED
                    | org.objectweb.asm.Opcodes.ACC_PRIVATE
                    | org.objectweb.asm.Opcodes.ACC_FINAL;
            int changes = 0;

            int classAccess = (node.access & ~mask) | (live.getModifiers() & mask);
            if (classAccess != node.access) {
                node.access = classAccess;
                changes++;
            }

            Map<String, Integer> fieldAccess = new HashMap<>();
            for (java.lang.reflect.Field field : live.getDeclaredFields()) {
                fieldAccess.put(field.getName() + ":" + org.objectweb.asm.Type.getDescriptor(
                        field.getType()), field.getModifiers());
            }
            for (FieldNode field : node.fields) {
                Integer access = fieldAccess.get(field.name + ":" + field.desc);
                if (access != null) {
                    int updated = (field.access & ~mask) | (access & mask);
                    if (updated != field.access) {
                        field.access = updated;
                        changes++;
                    }
                }
            }

            Map<String, Integer> methodAccess = new HashMap<>();
            for (java.lang.reflect.Method method : live.getDeclaredMethods()) {
                methodAccess.put(method.getName()
                                + org.objectweb.asm.Type.getMethodDescriptor(method),
                        method.getModifiers());
            }
            for (java.lang.reflect.Constructor<?> constructor : live.getDeclaredConstructors()) {
                methodAccess.put("<init>"
                                + org.objectweb.asm.Type.getConstructorDescriptor(constructor),
                        constructor.getModifiers());
            }
            for (MethodNode method : node.methods) {
                Integer access = methodAccess.get(method.name + method.desc);
                if (access != null) {
                    int updated = (method.access & ~mask) | (access & mask);
                    if (updated != method.access) {
                        method.access = updated;
                        changes++;
                    }
                }
            }

            if (changes == 0) {
                return bytes;
            }
            ClassWriter writer = new ClassWriter(reader, 0);
            node.accept(writer);
            ModReloader.LOGGER.info("[ModReloader] preserved {} load-time access flag(s) on {} "
                    + "(the class resource does not contain startup ATs)", changes, dotted);
            return writer.toByteArray();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot preserve the live access of {}", dotted, t);
            return bytes;
        }
    }

    /** Mixin wraps the real failure; keep the whole cause chain in the report. */
    private static String describe(Throwable t) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (!builder.isEmpty()) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            if (current.getCause() == current) {
                break;
            }
        }
        return builder.toString();
    }

    /**
     * @return {@code null} when the mixed class keeps its superclass, interfaces and
     *         fields, otherwise a human-readable description of what changed
     */
    /** Name of a static field pointing at a live instance of the same class, if any. */
    private static String liveSingleton(Class<?> type) {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!type.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Object value = UnsafeAccess.getStatic(type, field.getName());
                if (type.isInstance(value)) {
                    return field.getName();
                }
            } catch (Throwable ignored) {
                // not readable; keep looking
            }
        }
        return null;
    }

    private static String layoutChange(byte[] original, byte[] mixed) {
        if (original == null) {
            // No bytes captured before activation; cannot compare, so do not block.
            return null;
        }
        try {
            org.objectweb.asm.ClassReader before = new org.objectweb.asm.ClassReader(original);
            org.objectweb.asm.ClassReader after = new org.objectweb.asm.ClassReader(mixed);
            List<String> addedInterfaces = new ArrayList<>(List.of(after.getInterfaces()));
            addedInterfaces.removeAll(List.of(before.getInterfaces()));
            List<String> removedInterfaces = new ArrayList<>(List.of(before.getInterfaces()));
            removedInterfaces.removeAll(List.of(after.getInterfaces()));
            List<String> addedFields = new ArrayList<>(declaredFields(mixed));
            addedFields.removeAll(declaredFields(original));
            List<String> removedFields = new ArrayList<>(declaredFields(original));
            removedFields.removeAll(declaredFields(mixed));
            boolean superChanged = !java.util.Objects.equals(before.getSuperName(), after.getSuperName());
            if (!superChanged && addedInterfaces.isEmpty() && removedInterfaces.isEmpty()
                    && addedFields.isEmpty() && removedFields.isEmpty()) {
                return null;
            }
            StringBuilder builder = new StringBuilder();
            if (superChanged) {
                builder.append("wants superclass ").append(before.getSuperName())
                        .append(" -> ").append(after.getSuperName());
            }
            if (!addedInterfaces.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append("; ");
                }
                builder.append("adds interface(s) ").append(addedInterfaces);
            }
            if (!removedInterfaces.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append("; ");
                }
                builder.append("removes interface(s) ").append(removedInterfaces);
            }
            if (!addedFields.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append("; ");
                }
                builder.append("adds field(s) ").append(addedFields);
            }
            if (!removedFields.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append("; ");
                }
                builder.append("removes field(s) ").append(removedFields);
            }
            return builder.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Non-synthetic fields as {@code name:descriptor}, ignoring method bodies. */
    private static java.util.Set<String> declaredFields(byte[] classBytes) {
        java.util.Set<String> fields = new java.util.HashSet<>();
        new org.objectweb.asm.ClassReader(classBytes).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(
                            int access, String name, String descriptor, String signature,
                            Object value) {
                        if ((access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) == 0) {
                            fields.add(name + ":" + descriptor);
                        }
                        return null;
                    }
                },
                org.objectweb.asm.ClassReader.SKIP_CODE
                        | org.objectweb.asm.ClassReader.SKIP_DEBUG
                        | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return fields;
    }

    private static void redefine(Instrumentation instrumentation, Class<?> type, byte[] mixed,
                                 byte[] original, String dotted, List<String> redefined,
                                 List<String> errors) {
        if (!instrumentation.isModifiableClass(type)) {
            errors.add("target is not modifiable: " + dotted);
            return;
        }
        try {
            try (AutoCloseable blanket = MixinAgentGuard.blanket()) {
                // The mixed bytes are final: JBR's hierarchy update can redefine
                // subclasses too, and letting any transformer run again would
                // re-apply configs to classes that already contain them.
                instrumentation.redefineClasses(new ClassDefinition(type, mixed));
            }
            ClassBytesCache.put(dotted, mixed);
            redefined.add(dotted);
            SingletonFieldRepair.afterRedefine(dotted, type);
            ModReloader.LOGGER.info("[ModReloader] redefined {} with mixins ({} bytes)",
                    dotted, mixed.length);
        } catch (Throwable t) {
            dumpFailure(dotted, original, mixed);
            errors.add("redefine " + dotted + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            ModReloader.LOGGER.error("[ModReloader] mixin redefine failed: {}", dotted, t);
        }
    }

    /**
     * Keeps the exact bytes of a failed redefine. JBR can add fields/methods itself
     * (verified with the dev probe), so a {@code ClassFormatError} here means the
     * Mixin output needs inspection — not just a "JBR limitation" label.
     */
    private static void dumpFailure(String dotted, byte[] original, byte[] mixed) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("modreloader").resolve("mixin-failures");
            Files.createDirectories(dir);
            String safe = dotted.replaceAll("[^A-Za-z0-9_.-]", "_");
            if (original != null) {
                Files.write(dir.resolve(safe + ".original.class"), original);
            }
            Files.write(dir.resolve(safe + ".mixed.class"), mixed);
            ModReloader.LOGGER.warn("[ModReloader] mixin failure bytes dumped for {} to {}",
                    dotted, dir);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot dump mixin failure bytes for {}",
                    dotted, t);
        }
    }

    private static void redefineBack(Instrumentation instrumentation, Class<?> type,
                                     byte[] original, String dotted) throws Throwable {
        if (original == null) {
            throw new IllegalStateException("no original bytes captured for " + dotted);
        }
        try (AutoCloseable blanket = MixinAgentGuard.blanket()) {
            instrumentation.redefineClasses(new ClassDefinition(type, original));
        }
        ClassBytesCache.put(dotted, original);
        ModReloader.LOGGER.info("[ModReloader] rollback: restored class {} ({} bytes)",
                dotted, original.length);
    }

    private static Map<String, byte[]> captureOriginals(Set<String> targets,
                                                       Map<String, Class<?>> loaded) {
        Map<String, byte[]> originals = new HashMap<>();
        Instrumentation instrumentation;
        try {
            instrumentation = JvmAccess.get();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot capture original target bytes", t);
            return originals;
        }
        for (String target : targets) {
            String dotted = target.replace('/', '.');
            try {
                Class<?> type = loaded.get(dotted);
                // Read the class file from the defining loader instead of
                // retransforming. A retransform runs the whole transformer chain -
                // Mixin included - so by the time it hands us the "original" bytes it
                // has already applied the newly prepared configs to the live class.
                // Measured 2026-11-16: a refused interface mixin still ended up on
                // `EditBox` while `redefined=[]`, i.e. the capture itself mutated it.
                // Prefer what this runtime last installed (late AT / earlier add).
                byte[] bytes = ClassBytesCache.get(dotted);
                if (bytes == null) {
                    bytes = readClassResource(dotted);
                }
                if (bytes == null && type != null) {
                    ModReloader.LOGGER.warn(
                            "[ModReloader] no class-file resource for {}; falling back to a "
                                    + "retransform capture (this applies prepared mixins)", dotted);
                    bytes = ClassBytes.of(instrumentation, type);
                }
                if (bytes != null && type != null) {
                    // The resource is the pre-transformer shape; the live class already
                    // carries load-time ATs (NeoForge's own, not ours). Redefining from
                    // the resource would undo them (issue 67).
                    bytes = transplantLiveAccess(type, bytes, dotted);
                }
                if (bytes == null) {
                    ModReloader.LOGGER.warn("[ModReloader] no original bytes for {}; rollback limited",
                            dotted);
                } else {
                    originals.put(dotted, bytes);
                }
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot capture original bytes for {}: {}",
                        dotted, t.toString());
            }
        }
        return originals;
    }

    private static byte[] readClassResource(String dotted) {
        String path = dotted.replace('.', '/') + ".class";
        try (InputStream in = MixinSupport.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read class resource {}", path, e);
            return null;
        }
    }

    private static void removeConfig(String configName) {
        try {
            IMixinConfig config = findConfig(configName);
            Config handle = Mixins.getConfigs().stream()
                    .filter(candidate -> configName.equals(candidate.getName()))
                    .findFirst()
                    .orElse(null);
            if (config != null) {
                boolean prepared = false;
                try {
                    prepared = UnsafeAccess.getBoolean(config, config.getClass(), "prepared");
                } catch (Throwable t) {
                    ModReloader.LOGGER.warn("[ModReloader] cannot read MixinConfig.prepared", t);
                }
                REMOVED_CONFIGS.put(configName, new RemovedConfig(config, handle, prepared));
            }

            IMixinTransformer transformer = MixinRuntimeApi.getTransformer();
            Object processor = MixinRuntimeApi.getProcessor(transformer);
            List<Object> active = MixinRuntimeApi.getConfigs(processor);
            if (active != null) {
                active.removeIf(candidate -> candidate instanceof IMixinConfig c
                        && configName.equals(c.getName()));
                MixinRuntimeApi.setConfigs(processor, active);
            }
            List<Object> pending = MixinRuntimeApi.getPendingConfigs(processor);
            if (pending != null) {
                pending.removeIf(candidate -> candidate instanceof IMixinConfig c
                        && configName.equals(c.getName()));
                MixinRuntimeApi.setPendingConfigs(processor, pending);
            }
            UnsafeAccess.setBoolean(processor, processor.getClass(), "errorState", false);

            // Remove the handle from the global set so a later Mixin phase
            // cannot silently reactivate it; REMOVED_CONFIGS keeps the prepared
            // MixinConfig for retry (Mixins.addConfiguration would treat the
            // name as already registered and re-preparing would duplicate
            // MixinInfo state).
            if (handle != null) {
                Mixins.getConfigs().remove(handle);
            }
            ModReloader.LOGGER.info("[ModReloader] rollback: deactivated mixin config {}", configName);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot remove mixin config " + configName, t);
        }
    }

    private static IMixinConfig findConfig(String configName) {
        return Mixins.getConfigs().stream()
                .filter(candidate -> configName.equals(candidate.getName()))
                .map(Config::getConfig)
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Class<?>> loadedClasses() {
        Map<String, Class<?>> loaded = new HashMap<>();
        try {
            for (Class<?> type : JvmAccess.get().getAllLoadedClasses()) {
                if (!type.isArray()) {
                    loaded.put(type.getName(), type);
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot list loaded classes", t);
        }
        return loaded;
    }
}
