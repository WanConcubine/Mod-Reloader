package dev.modreloader.hotadd;

import com.google.common.base.CaseFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import dev.modreloader.ModReloader;
import dev.modreloader.reflect.JvmAccess;
import dev.modreloader.reflect.UnsafeAccess;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Applies the mod's {@code META-INF/enumextensions.json} at runtime.
 *
 * <p>NeoForge's {@code RuntimeEnumExtender} adds the constants while the enum
 * class is first loaded, so a hot-added mod loses them: the enum already
 * exists. Farmer's Delight then dies in {@code RecipeBookType.valueOf(
 * "FARMERSDELIGHT_COOKING")} when the cooking pot screen opens. Here the
 * constants are constructed with the enum's own constructor and appended to
 * {@code $VALUES}; {@code Enum.valueOf} sees them after its two caches on
 * {@code java.lang.Class} are refreshed.
 *
 * <p>Constants are reachable through {@code valueOf}/{@code values()} only; a
 * direct static-field reference in mod code would still fail, and constants
 * cannot be removed again on rollback.
 */
public final class EnumExtensions {
    private static final String ENTRY = "META-INF/enumextensions.json";
    private static final String ENUM_PROXY = "net.neoforged.fml.common.asm.enumextension.EnumProxy";

    private EnumExtensions() {
    }

    public static void apply(Path rawJar, Set<String> ids) {
        Path jar = rawJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            return;
        }
        JsonArray entries = readEntries(jar);
        if (entries == null || entries.isEmpty()) {
            return;
        }
        ClassLoader loader = EnumExtensions.class.getClassLoader();
        List<String> applied = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<Class<?>> extendedEnums = new java.util.LinkedHashSet<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String name = entry.has("name") ? entry.get("name").getAsString() : "?";
            try {
                String constant = addConstant(entry, loader);
                applied.add(constant);
                if (entry.has("enum")) {
                    extendedEnums.add(Class.forName(
                            entry.get("enum").getAsString().replace('/', '.'), false, loader));
                }
            } catch (Throwable t) {
                failed.add(name);
                ModReloader.LOGGER.warn(
                        "[ModReloader] enum extension {} of {} failed; that constant stays missing "
                                + "until the game restarts", name, ids, t);
            }
        }
        if (!applied.isEmpty()) {
            ModReloader.LOGGER.info("[ModReloader] enum extensions for {}: {}/{} applied {}",
                    ids, applied.size(), entries.size(), applied);
        }
        if (!failed.isEmpty()) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] enum extensions for {}: {} could not be applied {}; the mod may "
                            + "misbehave until restart (J2)", ids, failed.size(), failed);
        }
        for (Class<?> extended : extendedEnums) {
            refreshSwitchMaps(extended);
        }
        refreshRecipeBookTagFields();
        refreshClientRecipeBookTypes();
    }

    /**
     * A class that uses a classic {@code switch} over the enum keeps a
     * {@code $SwitchMap$<enum>} int[] sized from the constants present when it
     * first ran; a new ordinal past its end throws AIOOBE. Grow any such map of
     * an already-loaded class (new entries stay 0, i.e. the switch default).
     */
    private static void refreshSwitchMaps(Class<?> enumClass) {
        String fieldName = "$SwitchMap$" + enumClass.getName().replace('.', '$');
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return;
        }
        Instrumentation instrumentation;
        try {
            instrumentation = JvmAccess.get();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot inspect classes for {} switch maps",
                    enumClass.getSimpleName(), t);
            return;
        }
        int refreshed = 0;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            try {
                for (java.lang.reflect.Field field : loaded.getDeclaredFields()) {
                    if (!fieldName.equals(field.getName()) || field.getType() != int[].class) {
                        continue;
                    }
                    int[] map = UnsafeAccess.getStatic(loaded, field.getName());
                    if (map != null && map.length < constants.length) {
                        UnsafeAccess.setStatic(loaded, field.getName(),
                                Arrays.copyOf(map, constants.length));
                        refreshed++;
                    }
                }
            } catch (Throwable ignored) {
                // synthetic/hidden classes: nothing to refresh for them
            }
        }
        if (refreshed > 0) {
            ModReloader.LOGGER.info("[ModReloader] {} switch map(s) extended for {}",
                    refreshed, enumClass.getSimpleName());
        }
    }

    /**
     * ClientHooks captured {@code RecipeBookType.values()} when it initialized
     * (vanilla 4). The server now writes five book settings into
     * {@code ClientboundRecipePacket}; without refreshing this array the client
     * reads four groups of booleans and the packet desyncs ("packet was larger
     * than I expected").
     */
    private static void refreshClientRecipeBookTypes() {
        try {
            Class<?> hooks = Class.forName("net.neoforged.neoforge.client.ClientHooks");
            Class<?> type = Class.forName("net.minecraft.world.inventory.RecipeBookType");
            Object[] values = (Object[]) type.getEnumConstants();
            Object current = UnsafeAccess.getStatic(hooks, "RECIPE_BOOK_TYPES");
            if (values == null || current == null) {
                return;
            }
            int length = java.lang.reflect.Array.getLength(current);
            if (values.length <= length) {
                return;
            }
            Object array = java.lang.reflect.Array.newInstance(type, values.length);
            System.arraycopy(values, 0, array, 0, values.length);
            UnsafeAccess.setStatic(hooks, "RECIPE_BOOK_TYPES", array);
            UnsafeAccess.setStatic(hooks, "cachedFilteredTypes", null);
            ModReloader.LOGGER.info(
                    "[ModReloader] ClientHooks recipe book types extended: {} -> {} type(s)",
                    length, values.length);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot extend ClientHooks recipe book types; "
                    + "joining a world may fail with a packet decode error", t);
        }
    }

    /**
     * RecipeBookSettings froze its type-to-NBT-tag map while only the vanilla
     * types existed, because it looks at {@code RecipeBookType.getExtensionInfo()}
     * (which a runtime extension does not rewrite). Loading an old player then
     * leaves the map short of the new type and {@code replaceFrom} NPEs with
     * "invalid player data". Rebuild the map for every type now present.
     */
    private static void refreshRecipeBookTagFields() {
        try {
            Class<?> settings = Class.forName("net.minecraft.stats.RecipeBookSettings");
            Class<?> type = Class.forName("net.minecraft.world.inventory.RecipeBookType");
            Object[] types = type.getEnumConstants();
            if (types == null) {
                return;
            }
            Map<Object, Object> tags = UnsafeAccess.getStatic(settings, "TAG_FIELDS");
            if (tags == null) {
                return;
            }
            Map<Object, Object> extended = new HashMap<>(tags);
            for (Object constant : types) {
                if (extended.containsKey(constant)) {
                    continue;
                }
                String camel = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL,
                        ((Enum<?>) constant).name());
                extended.put(constant, Pair.of("is" + camel + "GuiOpen",
                        "is" + camel + "FilteringCraftable"));
            }
            if (extended.size() > tags.size()) {
                UnsafeAccess.setStatic(settings, "TAG_FIELDS", Map.copyOf(extended));
                ModReloader.LOGGER.info(
                        "[ModReloader] RecipeBookSettings tags extended: {} -> {} type(s)",
                        tags.size(), extended.size());
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot extend RecipeBookSettings tags; loading "
                    + "an old player may fail with invalid player data", t);
        }
    }

    private static JsonArray readEntries(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                JsonElement parsed = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    return null;
                }
                JsonElement entries = parsed.getAsJsonObject().get("entries");
                return entries != null && entries.isJsonArray() ? entries.getAsJsonArray() : null;
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read {} of {}; enum extensions skipped",
                    ENTRY, jar.getFileName(), t);
            return null;
        }
    }

    private static String addConstant(JsonObject entry, ClassLoader loader) throws Throwable {
        String enumName = entry.get("enum").getAsString();
        String constantName = entry.get("name").getAsString();
        String extraDescriptor = entry.has("constructor") ? entry.get("constructor").getAsString() : "()V";
        Class<?> enumClass = Class.forName(enumName.replace('/', '.'), false, loader);
        if (!enumClass.isEnum()) {
            throw new IllegalStateException(enumClass.getName() + " is not an enum");
        }
        Object[] current = enumClass.getEnumConstants();
        if (current == null) {
            throw new IllegalStateException(enumClass.getName() + " has no constants");
        }
        MethodType extra = MethodType.fromMethodDescriptorString(extraDescriptor, loader);
        List<Class<?>> parameterTypes = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        parameterTypes.add(String.class);
        parameterTypes.add(int.class);
        arguments.add(constantName);
        arguments.add(current.length);
        Object proxy = null;
        JsonElement parameters = entry.get("parameters");
        if (parameters != null && parameters.isJsonObject()) {
            JsonObject provider = parameters.getAsJsonObject();
            Class<?> providerClass = Class.forName(
                    provider.get("class").getAsString().replace('/', '.'), true, loader);
            proxy = UnsafeAccess.getStatic(providerClass, provider.get("field").getAsString());
            if (proxy == null) {
                throw new IllegalStateException("proxy field is null");
            }
            for (int i = 0; i < extra.parameterCount(); i++) {
                parameterTypes.add(extra.parameterType(i));
                arguments.add(getParameter(proxy, i));
            }
        } else if (extra.parameterCount() != 0) {
            throw new IllegalStateException("constructor takes parameters but none were provided");
        }
        MethodHandle constructor = UnsafeAccess.trusted().findConstructor(enumClass,
                MethodType.methodType(void.class, parameterTypes));
        Object constant = constructor.invokeWithArguments(arguments);
        Object[] extended = Arrays.copyOf(current, current.length + 1);
        extended[current.length] = constant;
        try {
            UnsafeAccess.setStatic(enumClass, "$VALUES", extended);
            refreshClassCaches(enumClass, extended);
        } catch (Throwable t) {
            UnsafeAccess.setStatic(enumClass, "$VALUES", current);
            throw t;
        }
        if (proxy != null) {
            setProxyValue(proxy, constant);
        }
        return enumClass.getSimpleName() + "." + constantName;
    }

    private static Object getParameter(Object proxy, int index) throws Throwable {
        Class<?> proxyClass = Class.forName(ENUM_PROXY, false, proxy.getClass().getClassLoader());
        MethodHandle getParameter = UnsafeAccess.trusted().findVirtual(proxyClass, "getParameter",
                MethodType.methodType(Object.class, int.class));
        return getParameter.invoke(proxy, index);
    }

    private static void setProxyValue(Object proxy, Object constant) {
        try {
            Class<?> proxyClass = Class.forName(ENUM_PROXY, false, proxy.getClass().getClassLoader());
            MethodHandle setValue = UnsafeAccess.trusted().findVirtual(proxyClass, "setValue",
                    MethodType.methodType(void.class, Enum.class));
            setValue.invoke(proxy, constant);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot set the EnumProxy value back", t);
        }
    }

    /**
     * {@code Enum.valueOf} and {@code EnumMap} read the enum universe from the
     * {@code java.lang.Class} instance's caches, built when the class
     * initialized. Refresh both so the appended constant is visible; the
     * directory is rebuilt lazily when cleared.
     */
    private static void refreshClassCaches(Class<?> enumClass, Object[] constants) throws Throwable {
        Object cached = UnsafeAccess.get(enumClass, Class.class, "enumConstants");
        if (cached != null) {
            UnsafeAccess.set(enumClass, Class.class, "enumConstants", constants);
        }
        UnsafeAccess.set(enumClass, Class.class, "enumConstantDirectory", null);
    }
}