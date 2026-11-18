package dev.modreloader.inspect;

import cpw.mods.jarhandling.JarContents;
import dev.modreloader.ModReloader;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only preflight for a candidate jar.
 *
 * <p>Uses FML's own metadata reader for mod ids/dependencies/mixins, then scans
 * class references for registry calls and native payloads. It never loads mod
 * classes and never mutates ModList.
 */
public final class ModInspector {
    private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;

    private ModInspector() {
    }

    public static ModInspection inspect(Path rawJar) {
        Path jar = rawJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            return failed(jar, "not a file");
        }
        if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return failed(jar, "not a .jar");
        }

        try {
            IModFile candidate = new JarModsDotTomlModFileReader()
                    .read(JarContents.of(jar), ModFileDiscoveryAttributes.DEFAULT);
            if (!(candidate instanceof ModFile modFile)) {
                return failed(jar, isGameLibrary(jar)
                        ? "library jar (FMLModType: GAMELIBRARY) declares no mods; "
                                + "hot-add needs a mod container"
                        : "not a NeoForge mod jar (no mods.toml)");
            }
            if (!modFile.identifyMods()) {
                return failed(jar, "cannot identify mods");
            }
            List<IModInfo> infos = modFile.getModInfos();
            if (infos.isEmpty()) {
                return failed(jar, isGameLibrary(jar)
                        ? "library jar (FMLModType: GAMELIBRARY) declares no mods; "
                                + "hot-add needs a mod container"
                        : "jar declares no mods");
            }
            IModFileInfo rawFileInfo = modFile.getModFileInfo();
            if (!(rawFileInfo instanceof ModFileInfo fileInfo)) {
                return failed(jar, "no ModFileInfo");
            }

            List<ModInspection.Mod> mods = new ArrayList<>();
            Set<String> hotIds = new HashSet<>();
            boolean alreadyLoaded = false;
            for (IModInfo info : infos) {
                mods.add(new ModInspection.Mod(info.getModId(), info.getDisplayName(),
                        info.getVersion().toString()));
                hotIds.add(info.getModId());
                alreadyLoaded |= ModList.get().isLoaded(info.getModId());
            }

            boolean mixins = modFile.getMixinConfigs() != null && !modFile.getMixinConfigs().isEmpty();
            List<String> mixinNames = modFile.getMixinConfigs() == null ? List.of()
                    : modFile.getMixinConfigs().stream().map(ModFileParser.MixinConfig::config).toList();

            List<ModInspection.Dependency> dependencies = new ArrayList<>();
            for (IModInfo info : infos) {
                for (IConfigurable dependency : fileInfo.getConfigList("dependencies", info.getModId())) {
                    Optional<String> id = dependency.getConfigElement("modId");
                    if (id.isEmpty()) {
                        continue;
                    }
                    Optional<String> type = dependency.getConfigElement("type");
                    Optional<String> range = dependency.getConfigElement("versionRange");
                    boolean required = type.map("required"::equalsIgnoreCase).orElse(false);
                    boolean satisfied = hotIds.contains(id.get())
                            || (ModList.get() != null && ModList.get().isLoaded(id.get()));
                    dependencies.add(new ModInspection.Dependency(id.get(),
                            range.orElse(""), required, satisfied));
                }
            }

            boolean sideFlag = fileInfo.getConfigElement("clientSideOnly")
                    .map(value -> String.valueOf(value))
                    .map(value -> "true".equalsIgnoreCase(value))
                    .orElse(false);

            ScanResult scan = scanJar(jar);
            // The metadata flag is authoritative for "this mod is client-only". Bytecode
            // references are a *warning* only: client+server mods reference client
            // classes in code that the server never loads (Jade does), while a mod that
            // loads them on the startup path fails on a dedicated server even with
            // side="BOTH" (EasyLAN registers a ScreenEvent listener in its constructor).
            // Static scanning cannot tell those apart; the add path has a runtime
            // fallback (rollback), and the report says so instead of refusing.
            boolean clientSideOnly = sideFlag;
            ModInspection.Tier tier = scan.nativeCode() ? ModInspection.Tier.HUANGHOU
                    : scan.registry() ? ModInspection.Tier.GUIFEI
                    : mixins ? ModInspection.Tier.FEI
                    : ModInspection.Tier.PIN;

            List<String> notes = new ArrayList<>();
            if (!mixinNames.isEmpty()) {
                notes.add("mixins: " + mixinNames);
            }
            if (alreadyLoaded) {
                notes.add("already loaded");
            }
            for (ModInspection.Dependency dependency : dependencies) {
                if (dependency.required() && !dependency.satisfied()) {
                    notes.add("missing dependency: " + dependency.modId() + " " + dependency.versionRange());
                }
            }
            if (tier == ModInspection.Tier.FEI) {
                notes.add(JbrSupport.describe());
            }
            if (sideFlag) {
                notes.add("clientSideOnly=true");
            }
            if (!scan.clientClasses().isEmpty()) {
                List<String> sample = scan.clientClasses().stream().limit(4).toList();
                notes.add("references client classes: " + sample
                        + (scan.clientClasses().size() > sample.size() ? " …" : ""));
            }

            return new ModInspection(jar, jar.getFileName().toString(), List.copyOf(mods), tier,
                    mixins, scan.registry(), scan.nativeCode(), alreadyLoaded, clientSideOnly,
                    scan.accessTransformers(),
                    List.copyOf(dependencies), List.copyOf(notes), null);
        } catch (UncheckedIOException e) {
            ModReloader.LOGGER.warn("[ModReloader] preflight failed for {}: not a valid zip/jar", jar);
            return failed(jar, "not a valid jar/zip (corrupt or incomplete)");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] preflight failed for {}", jar, t);
            return failed(jar, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * A NeoForge "game library" (MANIFEST {@code FMLModType: GAMELIBRARY}) is a
     * dependency jar, not a mod: it has no ModContainer, so it cannot be added by
     * the hot-add pipeline. Detecting it lets the UI say that instead of the
     * misleading "jar declares no mods" (mezz_config standalone).
     */
    public static boolean isGameLibrary(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) {
                String text = new String(zip.getInputStream(manifest).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("fmlmodtype:") && lower.contains("gamelibrary")) {
                        return true;
                    }
                }
            }
            // Nested libraries (codecui) declare it in the toml instead of the
            // manifest; FML's JarModsDotTomlModFileReader reads exactly this key.
            for (String toml : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                ZipEntry entry = zip.getEntry(toml);
                if (entry == null) {
                    continue;
                }
                String text = new String(zip.getInputStream(entry).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String lower = line.split("#", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (lower.startsWith("modtype") && lower.contains("library")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Unreadable metadata: fall back to the generic message.
        }
        return false;
    }

    private static ModInspection failed(Path jar, String error) {
        return new ModInspection(jar, jar.getFileName().toString(), List.of(), ModInspection.Tier.PIN,
                false, false, false, false, false, false, List.of(), List.of(), error);
    }

    private static ScanResult scanJar(Path jar) {
        boolean registry = false;
        boolean nativeCode = false;
        boolean accessTransformers = false;
        java.util.TreeSet<String> clientClasses = new java.util.TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
                        || name.startsWith("meta-inf/native-image/")
                        || name.equals("meta-inf/coremods.json")) {
                    nativeCode = true;
                }
                if (!name.endsWith(".class") || entry.getSize() > MAX_CLASS_BYTES) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    ClassReader reader = new ClassReader(bytes);
                    RegistryVisitor visitor = new RegistryVisitor();
                    reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    registry |= visitor.registry;
                    clientClasses.addAll(visitor.clientClasses);
                } catch (Throwable ignored) {
                    // A single malformed class should not break the preflight.
                }
            }
            try {
                // An empty AT file (Moonlight's codecui ships one) has no rules and
                // must not refuse the add. Custom [[accessTransformers]] paths count too.
                accessTransformers = AccessTransformerRules.hasRules(zip);
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot inspect access transformers of {}", jar, t);
                accessTransformers = true;
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot scan jar {}", jar, t);
        }
        return new ScanResult(registry, nativeCode, accessTransformers, List.copyOf(clientClasses));
    }

    /**
     * @param clientClasses sample of client-only class names the jar references
     *                      ({@code net/minecraft/client/**}, blaze3d); NeoForge refuses
     *                      such classes on a dedicated server, so a jar that uses them
     *                      on the startup path cannot be hot-added there. Only a sample
     *                      is kept — it exists to explain the refusal.
     */
    private record ScanResult(boolean registry, boolean nativeCode, boolean accessTransformers,
                              List<String> clientClasses) {
    }

    private static final class RegistryVisitor extends ClassVisitor {
        private boolean registry;
        /** Sample of client-only classes referenced anywhere in the jar. */
        private final java.util.TreeSet<String> clientClasses = new java.util.TreeSet<>();

        RegistryVisitor() {
            super(Opcodes.ASM9);
        }

        private void noteClientClass(String internalName) {
            if (internalName == null || clientClasses.size() >= 32) {
                return;
            }
            if (internalName.startsWith("net/minecraft/client/")
                    || internalName.startsWith("com/mojang/blaze3d/")) {
                clientClasses.add(internalName.replace('/', '.'));
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            // A DeferredRegister held in a field is a registration intent even when the
            // register() call itself lives in another class.
            registry |= RegistrySignal.referencesDeferredRegister(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            registry |= RegistrySignal.referencesDeferredRegister(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                    // Registration only: a lookup like BuiltInRegistries.ITEM.get(...) is
                    // not registry content (see RegistrySignal).
                    registry |= RegistrySignal.isRegistrationCall(owner, methodName);
                    registry |= RegistrySignal.referencesDeferredRegister(owner);
                    registry |= RegistrySignal.referencesDeferredRegister(methodDescriptor);
                    noteClientClass(owner);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName,
                                           String fieldDescriptor) {
                    registry |= RegistrySignal.referencesDeferredRegister(owner);
                    registry |= RegistrySignal.referencesDeferredRegister(fieldDescriptor);
                    noteClientClass(owner);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    registry |= RegistrySignal.referencesDeferredRegister(type);
                    noteClientClass(type);
                }
            };
        }
    }
}
