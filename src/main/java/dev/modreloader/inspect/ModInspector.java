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
    private static final Set<String> REGISTRY_OWNERS = Set.of(
            "net/minecraft/core/Registry",
            "net/minecraft/core/Registries",
            "net/minecraft/core/registries/BuiltInRegistries",
            "net/neoforged/neoforge/registries/DeferredRegister",
            "net/neoforged/neoforge/registries/NeoForgeRegistries",
            "net/neoforged/neoforge/event/RegisterEvent");
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
                return failed(jar, "not a NeoForge mod jar (no mods.toml)");
            }
            if (!modFile.identifyMods()) {
                return failed(jar, "cannot identify mods");
            }
            List<IModInfo> infos = modFile.getModInfos();
            if (infos.isEmpty()) {
                return failed(jar, "jar declares no mods");
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

            boolean clientSideOnly = fileInfo.getConfigElement("clientSideOnly")
                    .map(value -> String.valueOf(value))
                    .map(value -> "true".equalsIgnoreCase(value))
                    .orElse(false);

            ScanResult scan = scanJar(jar);
            ModInspection.Tier tier = scan.nativeCode() ? ModInspection.Tier.T5
                    : scan.registry() ? ModInspection.Tier.T4
                    : mixins ? ModInspection.Tier.T3
                    : ModInspection.Tier.T2;

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
            if (tier == ModInspection.Tier.T3) {
                notes.add(JbrSupport.describe());
            }
            if (clientSideOnly) {
                notes.add("clientSideOnly=true");
            }

            return new ModInspection(jar, jar.getFileName().toString(), List.copyOf(mods), tier,
                    mixins, scan.registry(), scan.nativeCode(), alreadyLoaded, clientSideOnly,
                    List.copyOf(dependencies), List.copyOf(notes), null);
        } catch (UncheckedIOException e) {
            ModReloader.LOGGER.warn("[ModReloader] preflight failed for {}: not a valid zip/jar", jar);
            return failed(jar, "not a valid jar/zip (corrupt or incomplete)");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] preflight failed for {}", jar, t);
            return failed(jar, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static ModInspection failed(Path jar, String error) {
        return new ModInspection(jar, jar.getFileName().toString(), List.of(), ModInspection.Tier.T2,
                false, false, false, false, false, List.of(), List.of(), error);
    }

    private static ScanResult scanJar(Path jar) {
        boolean registry = false;
        boolean nativeCode = false;
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
                } catch (Throwable ignored) {
                    // A single malformed class should not break the preflight.
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot scan jar {}", jar, t);
        }
        return new ScanResult(registry, nativeCode);
    }

    private record ScanResult(boolean registry, boolean nativeCode) {
    }

    private static final class RegistryVisitor extends ClassVisitor {
        private boolean registry;

        RegistryVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                    registry |= REGISTRY_OWNERS.contains(owner);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName,
                                           String fieldDescriptor) {
                    registry |= REGISTRY_OWNERS.contains(owner);
                }
            };
        }
    }
}
