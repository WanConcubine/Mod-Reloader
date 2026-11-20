package dev.modreloader.hotadd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modreloader.ModReloader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import dev.modreloader.reflect.JvmAccess;

/**
 * A1.0 read-only probe.
 *
 * <p>Reads a mod jar, finds its mixin configs/classes, and reports:
 * target classes, whether they are already loaded, and which fields/methods
 * the mixins would add (layout changes). It never redefines or loads classes.
 */
public final class MixinProbe {
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";

    private MixinProbe() {
    }

    public static List<String> probe(Path rawJar) {
        List<String> lines = new ArrayList<>();
        Path jar = rawJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            lines.add("[MixinProbe] not a file: " + jar);
            return lines;
        }

        boolean jbr = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT).contains("jetbrains");
        boolean enhanced = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"));
        lines.add("[MixinProbe] jar=" + jar);
        lines.add("[MixinProbe] jbr=" + jbr + " enhancedRedefinition=" + enhanced);

        Map<String, Class<?>> loaded;
        try {
            Instrumentation instrumentation = JvmAccess.get();
            loaded = new HashMap<>();
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (!type.isArray()) {
                    loaded.put(type.getName(), type);
                }
            }
        } catch (Throwable t) {
            lines.add("[MixinProbe] cannot inspect loaded classes: " + t);
            return lines;
        }

        int configCount = 0;
        int mixinCount = 0;
        int addedFields = 0;
        int addedMethods = 0;
        Set<String> targets = new LinkedHashSet<>();
        Set<String> loadedTargets = new LinkedHashSet<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> configs = findMixinConfigs(zip);
            for (String configName : configs) {
                configCount++;
                JsonObject json = readJson(zip, configName);
                if (json == null) {
                    lines.add("[MixinProbe] cannot parse config: " + configName);
                    continue;
                }
                String pkg = json.has("package") ? json.get("package").getAsString() : "";
                List<String> mixinClasses = new ArrayList<>();
                for (String key : List.of("mixins", "client", "server")) {
                    if (!json.has(key) || !json.get(key).isJsonArray()) {
                        continue;
                    }
                    JsonArray array = json.getAsJsonArray(key);
                    for (JsonElement element : array) {
                        if (!element.isJsonPrimitive()) {
                            continue;
                        }
                        String entry = element.getAsString();
                        mixinClasses.add(entry.contains(".") || pkg.isEmpty() ? entry : pkg + "." + entry);
                    }
                }
                lines.add("[MixinProbe] config " + configName + ": " + mixinClasses.size() + " mixin classes");

                for (String mixinClass : mixinClasses) {
                    mixinCount++;
                    byte[] bytes = readEntry(zip, mixinClass.replace('.', '/') + ".class");
                    if (bytes == null) {
                        lines.add("[MixinProbe]   missing mixin class: " + mixinClass);
                        continue;
                    }
                    ClassNode node = readNode(bytes);
                    if (node == null) {
                        lines.add("[MixinProbe]   cannot parse mixin class: " + mixinClass);
                        continue;
                    }
                    AnnotationNode mixin = findAnnotation(node.visibleAnnotations, MIXIN_DESC);
                    if (mixin == null) {
                        mixin = findAnnotation(node.invisibleAnnotations, MIXIN_DESC);
                    }
                    List<String> mixinTargets = mixin == null ? List.of() : targetsOf(mixin);
                    boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;

                    lines.add("[MixinProbe] mixin " + mixinClass
                            + " (interface=" + isInterface + ") targets=" + mixinTargets);
                    if (mixinTargets.isEmpty()) {
                        lines.add("[MixinProbe]   no @Mixin targets");
                        continue;
                    }

                    for (String target : mixinTargets) {
                        targets.add(target);
                        String dotted = target.replace('/', '.');
                        Class<?> targetClass = loaded.get(dotted);
                        boolean targetLoaded = targetClass != null;
                        if (targetLoaded) {
                            loadedTargets.add(target);
                        }
                        lines.add("[MixinProbe]   target " + dotted + " loaded=" + targetLoaded
                                + (isInterface ? " (interface mixin; layout skipped)" : ""));
                        if (isInterface) {
                            continue;
                        }

                        Set<String> runningFields = new HashSet<>();
                        Set<String> runningMethods = new HashSet<>();
                        if (targetClass != null) {
                            for (var field : targetClass.getDeclaredFields()) {
                                runningFields.add(field.getName() + ":" + Type.getDescriptor(field.getType()));
                            }
                            for (var method : targetClass.getDeclaredMethods()) {
                                runningMethods.add(method.getName() + Type.getMethodDescriptor(method));
                            }
                        }

                        int targetFields = 0;
                        int targetMethods = 0;
                        for (FieldNode field : node.fields) {
                            if (hasAnnotation(field.visibleAnnotations, SHADOW_DESC)
                                    || hasAnnotation(field.invisibleAnnotations, SHADOW_DESC)) {
                                continue;
                            }
                            String key = field.name + ":" + field.desc;
                            if (targetClass != null && runningFields.contains(key)) {
                                lines.add("[MixinProbe]     = existing field " + field.name);
                                continue;
                            }
                            targetFields++;
                            lines.add("[MixinProbe]     + field " + field.name + ":" + field.desc);
                        }
                        for (MethodNode method : node.methods) {
                            if (method.name.startsWith("<")) {
                                continue;
                            }
                            if (hasAnnotation(method.visibleAnnotations, SHADOW_DESC)
                                    || hasAnnotation(method.invisibleAnnotations, SHADOW_DESC)) {
                                continue;
                            }
                            String key = method.name + method.desc;
                            if (targetClass != null && runningMethods.contains(key)) {
                                lines.add("[MixinProbe]     = existing method " + method.name);
                                continue;
                            }
                            targetMethods++;
                            lines.add("[MixinProbe]     + method " + method.name + method.desc);
                        }
                        addedFields += targetFields;
                        addedMethods += targetMethods;
                        lines.add("[MixinProbe]   layout: +" + targetFields + " fields, +"
                                + targetMethods + " methods");
                    }
                }
            }
        } catch (Throwable t) {
            lines.add("[MixinProbe] probe failed: " + t);
            ModReloader.LOGGER.warn("[MixinProbe] probe failed for {}", jar, t);
            return lines;
        }

        lines.add("[MixinProbe] summary: configs=" + configCount + " mixins=" + mixinCount
                + " targets=" + targets.size() + " loadedTargets=" + loadedTargets.size()
                + " addedFields=" + addedFields + " addedMethods=" + addedMethods);
        String verdict;
        if (addedFields == 0 && addedMethods == 0) {
            verdict = "NO_LAYOUT_CHANGE";
        } else if (jbr && enhanced) {
            verdict = "JBR_REDEFINE";
        } else {
            verdict = "SIDECAR_REQUIRED";
        }
        lines.add("[MixinProbe] verdict=" + verdict);
        return lines;
    }

    private static List<String> findMixinConfigs(ZipFile zip) {
        List<String> configs = new ArrayList<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".json") || lower.contains("refmap")) {
                continue;
            }
            String base = lower.substring(lower.lastIndexOf('/') + 1);
            if (base.endsWith(".mixins.json") || base.endsWith(".mixin.json") || base.equals("mixins.json")) {
                configs.add(name);
            }
        }
        return configs;
    }

    private static JsonObject readJson(ZipFile zip, String name) {
        byte[] bytes = readEntry(zip, name);
        if (bytes == null) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > 4 * 1024 * 1024) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static ClassNode readNode(byte[] bytes) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        return findAnnotation(annotations, desc) != null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static List<String> targetsOf(AnnotationNode mixin) {
        List<String> targets = new ArrayList<>();
        addTargets(targets, annotationValue(mixin, "value"));
        addTargets(targets, annotationValue(mixin, "targets"));
        return targets;
    }

    private static void addTargets(List<String> targets, Object value) {
        if (value instanceof Type type) {
            targets.add(type.getInternalName());
        } else if (value instanceof String string) {
            targets.add(string.replace('.', '/'));
        } else if (value instanceof List<?> list) {
            for (Object element : list) {
                addTargets(targets, element);
            }
        }
    }
}
