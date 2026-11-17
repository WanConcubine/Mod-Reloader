package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Dev-only functional check for the late-coremod path: hot-add jecharacters at the
 * title screen and prove that pinyin search is actually wired into JEI.
 *
 * <p>The engine-level PASS of a coremod jar only says the module and ModList are
 * fine; the feature lives in the bytecode the coremod would have rewritten at
 * launch. This check asserts the functional outcome instead:
 *
 * <ol>
 *   <li>JEI's search class {@code mezz.jei.gui.search.ElementSearchLowMem} now
 *       refers to {@code me.towdium.jecharacters.utils.Match} - the transform was
 *       applied (redefined if the class was loaded, via the on-load transformer
 *       otherwise);</li>
 *   <li>calling that class's own {@code matches} with a CJK name and a pinyin query
 *       returns true - before the transform the same call is {@code "铁锭".contains("tieding")}
 *       and returns false, so this is an A/B assertion, not a smoke test;</li>
 *   <li>{@code Match.contains("铁锭", "tieding")} returns true, so the pinyin engine
 *       (PinIn + dictionaries) is alive in this client.</li>
 * </ol>
 *
 * <p>{@code -PverifyPinyin=<jecharacters jar>} with <b>JEI installed in run/mods</b>.
 * Nothing is clicked: the title screen is enough for the class-level assertion.
 */
public final class PinyinCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int AFTER_ADD_TICKS = 40;
    private static final int TIMEOUT_TICKS = 20 * 300;

    private static final String JEI_SEARCH = "mezz.jei.gui.search.ElementSearchLowMem";
    private static final String JECHARACTERS = "me.towdium.jecharacters";
    private static final String MATCH = JECHARACTERS + ".utils.Match";
    /** The same name as it appears in a class file constant pool (internal form). */
    private static final String MATCH_INTERNAL = MATCH.replace('.', '/');
    private static final String CJK_NAME = "铁锭";
    private static final String PINYIN_QUERY = "tieding";

    private static Path jar;
    private static int ticks;
    private static int totalTicks;
    private static boolean added;
    private static boolean done;

    private PinyinCheck() {
    }

    public static void start(Path target) {
        if (done || jar != null) {
            return;
        }
        jar = target.toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[PinyinCheck] hot-add {} and verify pinyin wiring into JEI", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (done) {
            return;
        }
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[PinyinCheck] TIMEOUT after {}s", TIMEOUT_TICKS / 20);
            finish(false, List.of("timeout"));
            return;
        }
        if (totalTicks < TITLE_SETTLE_TICKS) {
            return;
        }
        if (!added) {
            added = true;
            HotAddEngine.Result result;
            try {
                result = HotAddEngine.add(jar);
            } catch (Throwable t) {
                finish(false, List.of("hot-add threw " + t.getClass().getSimpleName() + ": "
                        + t.getMessage()));
                return;
            }
            ModReloader.LOGGER.info("[PinyinCheck] hot-add result: ok={} stage={} kind={} msg={}",
                    result.ok(), result.stage(), result.kind(), result.message());
            if (!result.ok()) {
                finish(false, List.of("hot-add failed at " + result.stage() + "/" + result.kind()
                        + ": " + result.message()));
                return;
            }
            if (!result.message().contains("coremods ")) {
                // The coremod stage produces this fragment; without it nothing was applied.
                ModReloader.LOGGER.warn("[PinyinCheck] result message has no coremod fragment: {}",
                        result.message());
            }
            ticks = 0;
            return;
        }
        if (++ticks < AFTER_ADD_TICKS) {
            return;
        }
        check();
    }

    private static void check() {
        List<String> problems = new ArrayList<>();
        ClassLoader loader = PinyinCheck.class.getClassLoader();
        try {
            Class<?> jeiSearch = Class.forName(JEI_SEARCH, false, loader);
            // The engine records the bytes it actually wrote. Reading the class back
            // with a retransform would start from the pre-transform bytes for a
            // non-retransformable transformer, so it cannot prove anything.
            byte[] applied = dev.modreloader.hotadd.LateCoremod.transformedBytes(JEI_SEARCH);
            if (applied == null) {
                problems.add("the coremod did not write " + JEI_SEARCH + "; transformed classes: "
                        + dev.modreloader.hotadd.LateCoremod.transformedClasses());
            } else if (!contains(applied, MATCH_INTERNAL)) {
                problems.add("the bytes applied to " + JEI_SEARCH + " have no reference to "
                        + MATCH_INTERNAL + " - the transformer ran but did not rewrite the method");
            }
            boolean wired = jeiMatchesPinyin(jeiSearch, loader);
            if (!wired) {
                problems.add(JEI_SEARCH + ".matches(" + CJK_NAME + ", " + PINYIN_QUERY
                        + ") returned false - the search path did not become pinyin-aware");
            }
        } catch (ClassNotFoundException e) {
            problems.add("JEI is not installed (" + JEI_SEARCH
                    + " not found); put the JEI jar in run/mods for this check");
        } catch (Throwable t) {
            problems.add("JEI check threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        try {
            Class<?> match = Class.forName(MATCH, true, loader);
            Method contains = match.getMethod("contains", String.class, CharSequence.class);
            Object result = contains.invoke(null, CJK_NAME, PINYIN_QUERY);
            if (!Boolean.TRUE.equals(result)) {
                problems.add("Match.contains(" + CJK_NAME + ", " + PINYIN_QUERY + ") = " + result);
            }
        } catch (Throwable t) {
            problems.add("jecharacters Match check threw " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
        finish(problems.isEmpty(), problems);
    }

    /**
     * Calls JEI's own private static {@code matches(String, PrefixInfo, IListElementInfo)}
     * with a stub element whose name is {@link #CJK_NAME}. JEI is not a compile
     * dependency (it is a real mod jar in run/mods), so everything is reflective.
     */
    private static boolean jeiMatchesPinyin(Class<?> jeiSearch, ClassLoader loader) throws Exception {
        Class<?> prefixInfoClass = Class.forName("mezz.jei.core.search.PrefixInfo", false, loader);
        Class<?> stringsGetterClass = Class.forName("mezz.jei.core.search.PrefixInfo$IStringsGetter",
                false, loader);
        Class<?> modeGetterClass = Class.forName("mezz.jei.core.search.PrefixInfo$IModeGetter",
                false, loader);
        Class<?> elementInfoClass = Class.forName("mezz.jei.gui.ingredients.IListElementInfo",
                false, loader);
        Class<?> elementClass = Class.forName("mezz.jei.gui.ingredients.IListElement", false, loader);

        Object stringsGetter = proxy(stringsGetterClass, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getStrings" -> List.of(CJK_NAME, "Iron Ingot");
                    case "toString" -> "stringsGetter";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> defaultValue(method.getReturnType());
                });
        Object modeGetter = proxy(modeGetterClass, (proxy, method, args) ->
                switch (method.getName()) {
                    case "toString" -> "modeGetter";
                    case "hashCode" -> 2;
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> defaultValue(method.getReturnType());
                });
        Constructor<?> ctor = prefixInfoClass.getDeclaredConstructor(char.class, modeGetterClass,
                stringsGetterClass, java.util.function.Supplier.class);
        ctor.setAccessible(true);
        Object prefixInfo = ctor.newInstance(' ', modeGetter, stringsGetter,
                (java.util.function.Supplier<?>) () -> null);

        Object element = proxy(elementClass, (proxy, method, args) ->
                switch (method.getName()) {
                    case "isVisible" -> true;
                    case "toString" -> "element";
                    case "hashCode" -> 3;
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> defaultValue(method.getReturnType());
                });
        Object elementInfo = proxy(elementInfoClass, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getElement" -> element;
                    case "getNames" -> List.of(CJK_NAME);
                    case "getModNames" -> List.of();
                    case "getModIds" -> List.of();
                    case "getModNameForSorting" -> "";
                    case "toString" -> "elementInfo";
                    case "hashCode" -> 4;
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> defaultValue(method.getReturnType());
                });

        Method matches = jeiSearch.getDeclaredMethod("matches", String.class, prefixInfoClass,
                elementInfoClass);
        matches.setAccessible(true);
        Object result = matches.invoke(null, PINYIN_QUERY, prefixInfo, elementInfo);
        ModReloader.LOGGER.info("[PinyinCheck] {}.matches({}, {}, {}) = {}", JEI_SEARCH, PINYIN_QUERY,
                CJK_NAME, "stub", result);
        return Boolean.TRUE.equals(result);
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(PinyinCheck.class.getClassLoader(), new Class<?>[]{type},
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (type == List.class || type == Collection.class || type == Iterable.class) {
                return List.of();
            }
            if (type == Set.class) {
                return Set.of();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    private static boolean contains(byte[] bytes, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + pattern.length <= bytes.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void finish(boolean ok, List<String> problems) {
        done = true;
        if (ok) {
            ModReloader.LOGGER.info("[PinyinCheck] RESULT: PASS - jecharacters coremod was applied "
                    + "late and {} is pinyin-aware", JEI_SEARCH);
        } else {
            for (String problem : problems) {
                ModReloader.LOGGER.error("[PinyinCheck] problem: {}", problem);
            }
            ModReloader.LOGGER.error("[PinyinCheck] RESULT: FAIL - {}", problems);
        }
    }
}
