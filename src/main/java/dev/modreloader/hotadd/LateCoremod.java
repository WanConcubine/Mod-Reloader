package dev.modreloader.hotadd;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import dev.modreloader.ModReloader;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import dev.modreloader.reflect.JvmAccess;

/**
 * Applies a hot-added mod's {@code ICoreMod} transformers <em>after</em> start-up.
 *
 * <p>NeoForge runs {@code META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod}
 * while ModLauncher loads classes, so a coremod can rewrite a class before it is
 * first defined. A hot-added mod never went through that path: its jar was not on
 * the module path at launch, so its transformers were never invoked. The mod then
 * loads and looks fine while the feature the coremod implements is silently dead -
 * measured with jecharacters (its {@code String.contains} rewrite of JEI's search
 * class never ran, so pinyin search did nothing).
 *
 * <p>The gap is closed with the machinery the mixin stage already uses:
 *
 * <ul>
 *   <li>classes that are already loaded are read, transformed and handed to
 *       {@code Instrumentation.redefineClasses};</li>
 *   <li>classes that are not loaded yet get a {@link ClassFileTransformer} that
 *       applies the same transform when they are defined (the normal case, since
 *       JEI creates its search class lazily);</li>
 *   <li>every mutation registers an undo action in {@link Rollback}: the class is
 *       redefined back to the bytes it had before.</li>
 * </ul>
 *
 * <p>Scope: class-level transformers whose node type is {@code ClassNode} or
 * {@code byte[]} (what NeoForge coremods use). Method/field-level transformers are
 * reported as skipped instead of failing the add, and a target in a class loader we
 * cannot reach simply stays deferred. A transform that changes the layout of an
 * already-loaded class needs JBR's enhanced redefinition - same rule as
 * {@link MixinSupport}.
 */
public final class LateCoremod {
    /** ModLauncher reads exactly this service file. */
    private static final String SERVICE =
            "META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod";

    public record Report(List<String> coremods, List<String> transformers,
                         List<String> redefined, List<String> eager, List<String> deferred,
                         List<String> skipped, List<String> errors) {
        public Report {
            coremods = List.copyOf(coremods);
            transformers = List.copyOf(transformers);
            redefined = List.copyOf(redefined);
            eager = List.copyOf(eager);
            deferred = List.copyOf(deferred);
            skipped = List.copyOf(skipped);
            errors = List.copyOf(errors);
        }

        public static Report empty() {
            return new Report(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of());
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        public boolean applied() {
            return !redefined.isEmpty() || !eager.isEmpty();
        }

        /** One-line summary for logs and the hot-add result message. */
        public String summary() {
            List<String> parts = new ArrayList<>();
            if (!coremods.isEmpty()) {
                parts.add("coremods " + coremods);
            }
            if (!redefined.isEmpty()) {
                parts.add("redefined " + redefined);
            }
            if (!eager.isEmpty()) {
                parts.add("transformed-at-load " + eager);
            }
            if (!deferred.isEmpty()) {
                parts.add("deferred " + deferred.size() + " target(s) not loaded");
            }
            if (!skipped.isEmpty()) {
                parts.add("skipped " + skipped);
            }
            return parts.isEmpty() ? "none" : String.join(", ", parts);
        }
    }

    /**
     * Post-transform bytes of every class this process applied a coremod to, keyed
     * by binary name. Diagnostics only (the {@code /modreloader why}-style harness
     * needs to look at the bytes it claims to have produced); the last write wins.
     */
    private static final java.util.concurrent.ConcurrentMap<String, byte[]> TRANSFORMED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** @return the bytes written for {@code dotted}, or {@code null} if untouched */
    public static byte[] transformedBytes(String dotted) {
        return TRANSFORMED.get(dotted);
    }

    /** @return the classes a coremod was applied to in this process */
    public static Set<String> transformedClasses() {
        return Set.copyOf(TRANSFORMED.keySet());
    }

    @FunctionalInterface
    private interface BytesTransform {
        /** @return transformed bytes, or {@code null} when the transformer did not apply */
        byte[] apply(String className, byte[] bytes) throws Throwable;
    }

    private LateCoremod() {
    }

    /**
     * Builds the dedicated module layer for every jar that declares an
     * {@code ICoreMod}. This must run <b>before</b> the jar is injected into the game
     * layer: the nested coremod jar and the game-layer module created from it would
     * otherwise contain the same packages, and resolving one against the other fails
     * with {@code ResolutionException: ... exports package ...} (measured).
     *
     * @return one message per jar whose layer could not be created; empty on success
     */
    public static List<String> prepare(List<Path> jars) {
        List<String> errors = new ArrayList<>();
        for (Path jar : jars) {
            try {
                if (!readService(jar).isEmpty()) {
                    coremodLayer(jar);
                }
            } catch (Throwable t) {
                errors.add("cannot open the coremod layer of " + jar.getFileName() + ": "
                        + describe(t));
                ModReloader.LOGGER.error("[ModReloader] cannot open the coremod layer of {}",
                        jar, t);
            }
        }
        return errors;
    }

    /**
     * @param jars the hot-added jar <em>and</em> its extracted {@code META-INF/jarjar}
     *             jars: jecharacters ships its {@code ICoreMod} in the nested coremod
     *             jar, so scanning only the outer jar finds nothing
     */
    public static Report apply(List<Path> jars, Rollback rollback) {
        List<Path> declared = new ArrayList<>();
        List<String> implementations = new ArrayList<>();
        for (Path jar : jars) {
            try {
                List<String> services = readService(jar);
                if (!services.isEmpty()) {
                    declared.add(jar);
                    for (String implementation : services) {
                        if (!implementations.contains(implementation)) {
                            implementations.add(implementation);
                        }
                    }
                }
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot read the coremod service of {}", jar, t);
                return new Report(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of("cannot read " + SERVICE + " in " + jar.getFileName() + ": " + t));
            }
        }
        if (implementations.isEmpty()) {
            return Report.empty();
        }
        // FML gives every game-layer module the same loader, but a non-dev install
        // can differ; try our own loader first and fall back to the layer's finder.
        List<ClassLoader> loaders = loaders();

        List<String> coremods = new ArrayList<>();
        List<String> transformerNames = new ArrayList<>();
        List<String> redefined = Collections.synchronizedList(new ArrayList<>());
        List<String> eager = Collections.synchronizedList(new ArrayList<>());
        List<String> deferred = Collections.synchronizedList(new ArrayList<>());
        List<String> skipped = new ArrayList<>();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        // Pre-transform bytes of classes that were transformed while being defined.
        // Removing the transformer does not undo those bytes, so rollback redefines
        // each class back to this snapshot (layout-preserving transforms only).
        Map<String, byte[]> atLoadOriginals = Collections.synchronizedMap(new LinkedHashMap<>());

        // One chained byte transform per target class name.
        Map<String, BytesTransform> byClass = new LinkedHashMap<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (String implementation : implementations) {
            Object coremod;
            try {
                Class<?> type = Class.forName(implementation, true,
                        coremodLoader(declared, implementation));
                coremod = type.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                errors.add("coremod " + implementation + " cannot be instantiated: " + describe(t));
                ModReloader.LOGGER.error("[ModReloader] cannot instantiate coremod {}",
                        implementation, t);
                continue;
            }
            if (!(coremod instanceof ICoreMod)) {
                errors.add("coremod " + implementation + " is not an ICoreMod ("
                        + coremod.getClass().getName() + ")");
                continue;
            }
            coremods.add(implementation);
            Iterable<? extends ITransformer<?>> transformers;
            try {
                transformers = ((ICoreMod) coremod).getTransformers();
            } catch (Throwable t) {
                errors.add("coremod " + implementation + " failed in getTransformers(): "
                        + describe(t));
                ModReloader.LOGGER.error("[ModReloader] getTransformers failed for {}",
                        implementation, t);
                continue;
            }
            for (ITransformer<?> transformer : transformers) {
                try {
                    addTransformer(transformer, byClass, owners, transformerNames, skipped);
                } catch (Throwable t) {
                    errors.add("coremod " + implementation + " transformer is unusable: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
        if (byClass.isEmpty()) {
            return new Report(coremods, transformerNames, redefined, eager, deferred, skipped, errors);
        }

        Instrumentation instrumentation;
        try {
            instrumentation = JvmAccess.get();
        } catch (Throwable t) {
            errors.add("cannot obtain Instrumentation: " + t);
            return new Report(coremods, transformerNames, redefined, eager, deferred, skipped,
                    errors);
        }

        // Future loads. A target that is not loaded yet is transformed when it is
        // defined, exactly like at startup - just later.
        ClassFileTransformer onLoad = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                                    ProtectionDomain protectionDomain, byte[] bytes) {
                if (className == null || beingRedefined != null) {
                    return null;
                }
                String dotted = className.replace('/', '.');
                BytesTransform transform = byClass.get(dotted);
                if (transform == null) {
                    return null;
                }
                try {
                    byte[] out = transform.apply(dotted, bytes);
                    if (out != null) {
                        atLoadOriginals.putIfAbsent(dotted, bytes);
                        TRANSFORMED.put(dotted, out);
                        eager.add(dotted);
                        ModReloader.LOGGER.info(
                                "[ModReloader] coremod transformed {} as it was loaded ({} -> {} bytes)",
                                dotted, bytes.length, out.length);
                    }
                    return out;
                } catch (Throwable t) {
                    errors.add("coremod transform " + dotted + " at load: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                    ModReloader.LOGGER.error(
                            "[ModReloader] coremod transform failed for {}; the class loads raw",
                            dotted, t);
                    return null;
                }
            }
        };
        instrumentation.addTransformer(onLoad, false);
        rollback.add("coremod load transformer", () -> instrumentation.removeTransformer(onLoad));

        // Snapshot who is loaded *before* touching anything. Class.forName would
        // load a class as a side effect, so asking it per target would make every
        // target look "already loaded" and silently redefine the bytes the on-load
        // transformer just produced (measured: 10569 -> 10569).
        Set<String> loadedBefore = new LinkedHashSet<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            loadedBefore.add(type.getName());
        }

        for (Map.Entry<String, BytesTransform> entry : byClass.entrySet()) {
            String dotted = entry.getKey();
            BytesTransform transform = entry.getValue();
            if (!loadedBefore.contains(dotted)) {
                // Not loaded yet: the on-load transformer applies the bytes, and a
                // target whose mod is not installed simply stays deferred.
                try {
                    load(dotted, loaders, false);
                } catch (ClassNotFoundException e) {
                    deferred.add(dotted);
                } catch (Throwable t) {
                    deferred.add(dotted);
                    ModReloader.LOGGER.warn("[ModReloader] cannot eager-load coremod target {}: {}",
                            dotted, t.toString());
                }
                continue;
            }
            Class<?> loaded = findLoaded(dotted, loaders);
            if (loaded == null) {
                errors.add("coremod target " + dotted + " was loaded but is not reachable from "
                        + loaders);
                continue;
            }
            if (!instrumentation.isModifiableClass(loaded)) {
                errors.add("coremod target is not modifiable: " + dotted);
                continue;
            }
            byte[] original = readBytes(instrumentation, loaded);
            if (original == null) {
                errors.add("cannot read the current bytes of " + dotted
                        + "; refusing to redefine blind");
                continue;
            }
            byte[] mixed;
            try {
                mixed = transform.apply(dotted, original);
            } catch (Throwable t) {
                errors.add("coremod transform " + dotted + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                continue;
            }
            if (mixed == null) {
                skipped.add(dotted + " (transformer declined)");
                continue;
            }
            String layout = layoutChange(original, mixed);
            if (layout != null) {
                if (!dev.modreloader.inspect.JbrSupport.available()) {
                    errors.add("coremod layout: " + dotted + " " + layout
                            + " (the target is already loaded and this JVM cannot do enhanced "
                            + "redefinition; " + dev.modreloader.inspect.JbrSupport.describe()
                            + " - restart the game to load this mod)");
                    continue;
                }
                ModReloader.LOGGER.warn(
                        "[ModReloader] coremod changes the layout of the already-loaded {}: {}; "
                                + "attempting it because enhanced redefinition is available",
                        dotted, layout);
            }
            final byte[] before = original;
            rollback.add("restore class " + dotted, () ->
                    instrumentation.redefineClasses(new ClassDefinition(loaded, before)));
            try {
                instrumentation.redefineClasses(new ClassDefinition(loaded, mixed));
                TRANSFORMED.put(dotted, mixed);
                redefined.add(dotted);
                ModReloader.LOGGER.info(
                        "[ModReloader] coremod redefined {} ({} -> {} bytes, owners {})",
                        dotted, original.length, mixed.length, owners.get(dotted));
            } catch (Throwable t) {
                errors.add("coremod redefine " + dotted + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }

        if (!atLoadOriginals.isEmpty()) {
            rollback.add("restore coremod-transformed classes", () -> {
                for (Map.Entry<String, byte[]> entry : atLoadOriginals.entrySet()) {
                    Class<?> type = findLoaded(entry.getKey(), loaders);
                    if (type == null) {
                        ModReloader.LOGGER.warn(
                                "[ModReloader] rollback: cannot find coremod target {} to restore",
                                entry.getKey());
                        continue;
                    }
                    instrumentation.redefineClasses(
                            new ClassDefinition(type, entry.getValue()));
                    ModReloader.LOGGER.info(
                            "[ModReloader] rollback: restored coremod-transformed class {} ({} bytes)",
                            entry.getKey(), entry.getValue().length);
                }
            });
        }

        Report report = new Report(coremods, transformerNames, redefined, eager, deferred, skipped,
                errors);
        ModReloader.LOGGER.info("[ModReloader] coremod setup: {}", report.summary());
        if (!skipped.isEmpty()) {
            ModReloader.LOGGER.warn("[ModReloader] coremod targets not applied: {}", skipped);
        }
        return report;
    }

    /** Collects one transformer's class-level targets into the per-class chain. */
    private static void addTransformer(ITransformer<?> transformer, Map<String, BytesTransform> byClass,
                                       Map<String, List<String>> owners, List<String> names,
                                       List<String> skipped) {
        TargetType<?> targetType = transformer.getTargetType();
        Class<?> nodeType = targetType.getNodeType();
        BytesTransform transform;
        if (nodeType == ClassNode.class) {
            transform = nodeTransform(transformer);
        } else if (nodeType == byte[].class) {
            transform = bytesTransform(transformer);
        } else {
            skipped.add(transformer.getClass().getSimpleName() + " (node type "
                    + nodeType.getName() + " cannot be applied late)");
            return;
        }
        Set<String> targets = new LinkedHashSet<>();
        for (ITransformer.Target<?> target : transformer.targets()) {
            String name = target.className();
            if (name == null || name.isEmpty()) {
                continue;
            }
            targets.add(name.replace('/', '.'));
        }
        if (targets.isEmpty()) {
            skipped.add(transformer.getClass().getSimpleName() + " (no class-level targets)");
            return;
        }
        names.add(transformer.getClass().getSimpleName() + " -> " + targets.size() + " target(s)");
        for (String dotted : targets) {
            BytesTransform previous = byClass.get(dotted);
            if (previous == null) {
                byClass.put(dotted, transform);
            } else {
                byClass.put(dotted, (className, bytes) -> {
                    byte[] intermediate = previous.apply(className, bytes);
                    return intermediate == null ? null : transform.apply(className, intermediate);
                });
            }
            owners.computeIfAbsent(dotted, key -> new ArrayList<>())
                    .add(transformer.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static BytesTransform nodeTransform(ITransformer<?> transformer) {
        ITransformer<ClassNode> typed = (ITransformer<ClassNode>) transformer;
        return (className, bytes) -> {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            ClassNode out = typed.transform(node, context(className));
            if (out == null) {
                return null;
            }
            // COMPUTE_MAXS, not COMPUTE_FRAMES: a coremod rewrites instructions
            // inside existing methods and the frames it leaves behind stay valid.
            // Computing frames would need the full class hierarchy of every target
            // and would fail for the many optional mods a target list mentions.
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            out.accept(writer);
            return writer.toByteArray();
        };
    }

    @SuppressWarnings("unchecked")
    private static BytesTransform bytesTransform(ITransformer<?> transformer) {
        ITransformer<byte[]> typed = (ITransformer<byte[]>) transformer;
        return (className, bytes) -> typed.transform(bytes, context(className));
    }

    /**
     * A voting context for coremods that ask questions while transforming. ModLauncher
     * passes the real one; here the class already exists, there is no audit trail to
     * add and no vote can change the outcome. jecharacters ignores it entirely.
     */
    private static ITransformerVotingContext context(String className) {
        return (ITransformerVotingContext) Proxy.newProxyInstance(
                LateCoremod.class.getClassLoader(),
                new Class<?>[]{ITransformerVotingContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClassName" -> className;
                    case "doesClassExist" -> true;
                    case "getInitialClassSha256" -> new byte[0];
                    case "getAuditActivities" -> List.of();
                    case "getReason" -> "modreloader hot-add";
                    case "toString" -> "ModReloaderVotingContext[" + className + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> false;
                });
    }

    private record CoremodLayer(ModuleLayer layer, ClassLoader loader) {
    }

    /** One layer per coremod jar, keyed by content so a retry reuses it. */
    private static final Map<String, CoremodLayer> COREMOD_LAYERS = new LinkedHashMap<>();

    /**
     * A jar's coremod classes must not be loaded from the game layer module we
     * injected: the nested coremod jar shares the {@code me.towdium.jecharacters}
     * package with its outer mod, and the class loader resolves a resource through
     * exactly one module per package - measured: {@code targets.json} was invisible
     * and {@code JechCoreMod} refused to construct. ModLauncher gives coremod jars
     * their own layer at startup for the same reason; we do the same, against the
     * game layer so FML/Minecraft/ASM/gson are readable through the parent loader.
     */
    private static ClassLoader coremodLoader(List<Path> declared, String implementation)
            throws Exception {
        for (Path jar : declared) {
            if (readService(jar).contains(implementation)) {
                return coremodLayer(jar).loader();
            }
        }
        throw new ClassNotFoundException("no coremod jar in " + declared + " declares "
                + implementation);
    }

    private static synchronized CoremodLayer coremodLayer(Path jar) throws Exception {
        String hash = sha256(jar);
        CoremodLayer cached = COREMOD_LAYERS.get(hash);
        if (cached != null) {
            return cached;
        }
        Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("modreloader").resolve("coremods").resolve(hash.substring(0, 16));
        java.nio.file.Files.createDirectories(dir);
        // A module name component must be a Java identifier: the hash starts with a digit.
        String moduleName = "modreloader.coremod.c" + hash.substring(0, 16);
        Path copy = dir.resolve(moduleName + ".jar");
        if (!java.nio.file.Files.isRegularFile(copy)) {
            writeModuleJar(jar, copy, moduleName);
        }
        ModuleLayer gameLayer = net.neoforged.fml.loading.FMLLoader.getGameLayer();
        java.lang.module.ModuleFinder finder = java.lang.module.ModuleFinder.of(copy);
        if (finder.find(moduleName).isEmpty()) {
            throw new IllegalStateException("cannot read " + copy + " as module " + moduleName);
        }
        java.lang.module.Configuration configuration = gameLayer.configuration()
                .resolve(finder, java.lang.module.ModuleFinder.of(), Set.of(moduleName));
        ModuleLayer layer = gameLayer.defineModulesWithOneLoader(configuration,
                LateCoremod.class.getClassLoader());
        ClassLoader loader = layer.findLoader(moduleName);
        if (loader == null) {
            throw new IllegalStateException("no class loader for coremod module " + moduleName);
        }
        CoremodLayer result = new CoremodLayer(layer, loader);
        COREMOD_LAYERS.put(hash, result);
        ModReloader.LOGGER.info("[ModReloader] coremod layer {} loaded from {}", moduleName,
                jar.getFileName());
        return result;
    }

    /**
     * Copies the jar with an {@code Automatic-Module-Name} so the layer can hold it
     * without colliding with the game-layer module that {@link JarInJar} injected
     * for the same file.
     */
    private static void writeModuleJar(Path source, Path target, String moduleName)
            throws Exception {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (JarFile in = new JarFile(source.toFile());
             java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(
                     java.nio.file.Files.newOutputStream(target), manifest)) {
            Set<String> written = new LinkedHashSet<>();
            written.add("META-INF/MANIFEST.MF");
            for (JarEntry entry : java.util.Collections.list(in.entries())) {
                if (entry.isDirectory() || !written.add(entry.getName())) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream stream = in.getInputStream(entry)) {
                    stream.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }

    private static String sha256(Path jar) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = java.nio.file.Files.newInputStream(jar)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Loaders that can see the game layer: our own first, then FML's finder. */
    private static List<ClassLoader> loaders() {
        List<ClassLoader> loaders = new ArrayList<>();
        ClassLoader own = LateCoremod.class.getClassLoader();
        if (own != null) {
            loaders.add(own);
        }
        try {
            ClassLoader layer = net.neoforged.fml.loading.FMLLoader.getGameLayer()
                    .findLoader("neoforge");
            if (layer != null && !loaders.contains(layer)) {
                loaders.add(layer);
            }
        } catch (Throwable ignored) {
            // A dedicated server built differently still has our own loader.
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && !loaders.contains(context)) {
            loaders.add(context);
        }
        return loaders;
    }

    private static Class<?> load(String dotted, List<ClassLoader> loaders, boolean initialize)
            throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(dotted, initialize, loader);
            } catch (ClassNotFoundException e) {
                failure = e;
            }
        }
        throw failure == null ? new ClassNotFoundException(dotted) : failure;
    }

    /** @return the class when it is already loaded, otherwise {@code null} */
    private static Class<?> findLoaded(String dotted, List<ClassLoader> loaders) {
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(dotted, false, loader);
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        return null;
    }

    /**
     * Current bytes of a loaded class: retransforming is the only portable way to
     * get them, and the onLoad transformer deliberately ignores retransforms so the
     * capture cannot apply the new coremod early.
     */
    private static byte[] readBytes(Instrumentation instrumentation, Class<?> type) {
        try {
            return ClassBytes.of(instrumentation, type);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read class bytes for {}", type.getName(), t);
            return null;
        }
    }

    /**
     * @return {@code null} when the transform keeps superclass, interfaces, fields
     *         and methods; otherwise what changed. Those are the changes plain
     *         {@code redefineClasses} rejects.
     */
    private static String layoutChange(byte[] before, byte[] after) {
        try {
            Shape a = shape(before);
            Shape b = shape(after);
            List<String> changes = new ArrayList<>();
            if (!java.util.Objects.equals(a.superName(), b.superName())) {
                changes.add("superclass " + a.superName() + " -> " + b.superName());
            }
            Set<String> addedInterfaces = new TreeSet<>(b.interfaces());
            addedInterfaces.removeAll(a.interfaces());
            if (!addedInterfaces.isEmpty()) {
                changes.add("adds interface(s) " + addedInterfaces);
            }
            Set<String> addedMembers = new TreeSet<>(b.members());
            addedMembers.removeAll(a.members());
            if (!addedMembers.isEmpty()) {
                changes.add("adds member(s) " + addedMembers);
            }
            return changes.isEmpty() ? null : String.join("; ", changes);
        } catch (Throwable t) {
            // Unreadable bytes: let redefineClasses be the judge.
            return null;
        }
    }

    private record Shape(String superName, Set<String> interfaces, Set<String> members) {
    }

    private static Shape shape(byte[] bytes) {
        List<String> interfaces = new ArrayList<>();
        Set<String> members = new TreeSet<>();
        String[] superName = new String[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superClass, String[] implemented) {
                superName[0] = superClass;
                Collections.addAll(interfaces, implemented);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                members.add("field " + name + ":" + descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                members.add("method " + name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Shape(superName[0], new TreeSet<>(interfaces), members);
    }

    /**
     * Unwraps reflective wrappers: an {@code InvocationTargetException} with a
     * {@code null} message hides the actual cause (e.g. {@code NoClassDefFoundError}
     * in a static initializer), and that cause is the only useful text.
     */
    private static String describe(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        String text = root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
        return root == t ? text : t.getClass().getSimpleName() + " -> " + text;
    }

    private static List<String> readService(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(SERVICE);
            if (entry == null) {
                return names;
            }
            try (InputStream in = file.getInputStream(entry)) {
                String content = new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : content.split("\\R")) {
                    String trimmed = line.trim();
                    int comment = trimmed.indexOf('#');
                    if (comment >= 0) {
                        trimmed = trimmed.substring(0, comment).trim();
                    }
                    if (!trimmed.isEmpty()) {
                        names.add(trimmed);
                    }
                }
            }
        }
        return names;
    }
}
