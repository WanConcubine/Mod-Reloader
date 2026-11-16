package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.JvmAccess;
import dev.modreloader.inspect.AccessTransformerRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dev-only, read-only measurement for the "apply access transformers late" idea.
 *
 * <p>Access transformers only take effect when FML loads a class, which is why a
 * hot-added mod that relies on one fails at construction (see
 * {@code docs/features/hotadd-compatibility-matrix.md} §3.1). The one mechanism
 * that <em>could</em> work is applying an AT rule as a bytecode transform the
 * first time the target class is loaded — which is only possible for classes that
 * are <strong>not loaded yet</strong> when the mod is hot-added.
 *
 * <p>So the question this harness answers is quantitative: of the AT target
 * classes in real jars, how many are still unloaded at the two moments a hot-add
 * happens — at the title screen, and while in a world? If almost everything is
 * already loaded, "late AT" can only rescue a vanishing fraction and the
 * conclusion is "restart or soft restart", not "implement it".
 *
 * <p>Since 2026-11-16 it also answers the decisive question per jar: is
 * <strong>every</strong> runtime AT target still unloaded at this moment? (One
 * already-loaded target is enough to make late AT impossible.) Datagen-only
 * targets are excluded — a playing game never loads {@code RecipeProvider} — and
 * a jar whose ATs are entirely datagen-only is reported as such, because
 * refusing it "for having ATs" would be a false positive.
 *
 * <p>{@code -PverifyAtSpike=<jar>[,<jar>...]}. It creates a throwaway world to
 * get the in-world numbers; nothing is hot-added and nothing is written.
 */
public final class AtSpikeCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int WORLD_SETTLE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 240;

    private enum Stage {
        WAIT_TITLE, MEASURE_TITLE, OPEN_CREATE, WAIT_CREATE_SCREEN, WAIT_WORLD, MEASURE_WORLD, DONE
    }

    private static final List<Path> JARS = new ArrayList<>();
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean pressedCreate;

    private AtSpikeCheck() {
    }

    public static void start(String csv) {
        if (started) {
            return;
        }
        for (String part : csv.split(",")) {
            String raw = part.trim();
            if (!raw.isEmpty()) {
                JARS.add(Path.of(raw).toAbsolutePath().normalize());
            }
        }
        if (JARS.isEmpty()) {
            ModReloader.LOGGER.warn("[AtSpike] no jars given; not starting");
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[AtSpike] START jars={}", JARS);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[AtSpike] TIMEOUT at stage {}", stage);
            finish();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> {
                if (ticks < TITLE_SETTLE_TICKS || mc.getOverlay() != null) {
                    return;
                }
                measure("title screen", mc);
                stage = Stage.OPEN_CREATE;
                ticks = 0;
            }
            case OPEN_CREATE -> openCreateScreen(mc);
            case WAIT_CREATE_SCREEN -> waitForCreateScreen(mc);
            case WAIT_WORLD -> waitForWorld(mc);
            case MEASURE_WORLD -> {
                if (ticks < WORLD_SETTLE_TICKS) {
                    return;
                }
                measure("in world", mc);
                stage = Stage.DONE;
                ticks = 0;
            }
            case DONE -> {
                if (ticks >= 40) {
                    finish();
                }
            }
            default -> {
            }
        }
    }

    private static void openCreateScreen(Minecraft mc) {
        if (mc.getOverlay() != null) {
            return;
        }
        try {
            CreateWorldScreen.openFresh(mc, mc.screen);
            stage = Stage.WAIT_CREATE_SCREEN;
            ticks = 0;
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[AtSpike] cannot open the create-world screen", t);
            finish();
        }
    }

    private static void waitForCreateScreen(Minecraft mc) {
        if (!(mc.screen instanceof CreateWorldScreen screen)) {
            if (ticks > 20 * 30) {
                ModReloader.LOGGER.error("[AtSpike] create-world screen did not appear");
                finish();
            }
            return;
        }
        if (pressedCreate) {
            return;
        }
        AbstractButton button = findButton(screen, "selectWorld.create");
        if (button == null) {
            return;
        }
        pressedCreate = true;
        button.onPress();
        stage = Stage.WAIT_WORLD;
        ticks = 0;
    }

    private static void waitForWorld(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            if (ticks > 20 * 180) {
                ModReloader.LOGGER.error("[AtSpike] world did not load; reporting title-screen data only");
                finish();
            }
            return;
        }
        stage = Stage.MEASURE_WORLD;
        ticks = 0;
    }

    private static void finish() {
        started = false;
        ModReloader.LOGGER.info("[AtSpike] DONE");
        Minecraft.getInstance().stop();
    }

    private static void measure(String when, Minecraft mc) {
        Set<String> loaded = loadedClassNames();
        ModReloader.LOGGER.info("[AtSpike] --- {} : {} loaded classes, world={} ---",
                when, loaded.size(), mc.level != null);
        int jars = 0;
        int viableJars = 0;
        int datagenOnlyJars = 0;
        for (Path jar : JARS) {
            try {
                Verdict verdict = report(jar, when, loaded);
                if (verdict == null) {
                    continue;
                }
                jars++;
                if (verdict.viable()) {
                    viableJars++;
                }
                if (verdict.datagenOnly()) {
                    datagenOnlyJars++;
                }
            } catch (Throwable t) {
                ModReloader.LOGGER.error("[AtSpike] cannot measure {}", jar.getFileName(), t);
            }
        }
        ModReloader.LOGGER.info(
                "[AtSpike] SUMMARY [{}]: {} jar(s) with ATs; late-AT viable on {} of them "
                        + "({} jar(s) have datagen-only ATs)",
                when, jars, viableJars, datagenOnlyJars);
    }

    /** Per-jar answer to "could the late-AT idea work for this jar, at this moment?". */
    private record Verdict(boolean viable, boolean datagenOnly) {
    }

    private static Verdict report(Path jar, String when, Set<String> loaded) throws IOException {
        AccessTransformerRules rules = AccessTransformerRules.read(jar);
        if (rules.ruleCount() == 0) {
            ModReloader.LOGGER.info("[AtSpike] {} [{}]: no {}", jar.getFileName(), when,
                    AccessTransformerRules.ENTRY);
            return null;
        }
        Map<String, int[]> buckets = new HashMap<>();  // bucket -> {classes, loaded}
        List<String> runtimeLoaded = new ArrayList<>();
        Set<String> unloaded = new TreeSet<>();
        for (String target : rules.targets()) {
            boolean isLoaded = loaded.contains(target) || loaded.contains(target + "$");
            int[] bucket = buckets.computeIfAbsent(bucketOf(target), key -> new int[2]);
            bucket[0]++;
            if (isLoaded) {
                bucket[1]++;
                if (!AccessTransformerRules.isDatagenClass(target)) {
                    runtimeLoaded.add(target);
                }
            } else {
                unloaded.add(target);
            }
        }
        int classes = rules.targetCount();
        int classesLoaded = classes - unloaded.size();
        ModReloader.LOGGER.info(
                "[AtSpike] {} [{}]: {} | targets={} loaded={} unloaded={} ({}) | {}",
                jar.getFileName(), when, rules.describe(), classes, classesLoaded, unloaded.size(),
                percent(unloaded.size(), classes), describeBuckets(buckets));
        if (!unloaded.isEmpty()) {
            List<String> sample = unloaded.stream().limit(8).toList();
            ModReloader.LOGGER.info("[AtSpike] {} [{}]: unloaded sample {}", jar.getFileName(), when,
                    sample.size() == unloaded.size() ? sample
                            : sample + " ... (" + unloaded.size() + " total)");
        }

        // The decisive question: could a late AT (applied at hot-add time) still
        // reach every class this jar's AT rules need? One already-loaded runtime
        // target is enough to answer "no" - a class cannot be re-transformed
        // safely once it is defined.
        boolean datagenOnly = rules.datagenOnly();
        boolean viable = !datagenOnly && runtimeLoaded.isEmpty();
        ModReloader.LOGGER.info(
                "[AtSpike] {} [{}]: late-AT viable here: {} ({} runtime target(s), {} already "
                        + "loaded{})",
                jar.getFileName(), when, viable ? "YES" : "NO",
                rules.runtimeTargets().size(), runtimeLoaded.size(),
                datagenOnly ? "; every target is a datagen class"
                        : runtimeLoaded.isEmpty() ? "" : ": " + sampleOf(runtimeLoaded));
        return new Verdict(viable, datagenOnly);
    }

    /** Up to five names, for a one-line log. */
    private static List<String> sampleOf(List<String> names) {
        return names.size() <= 5 ? names : new ArrayList<>(names.subList(0, 5));
    }

    private static String describeBuckets(Map<String, int[]> buckets) {
        Map<String, String> parts = new java.util.TreeMap<>();
        for (Map.Entry<String, int[]> entry : buckets.entrySet()) {
            int[] counts = entry.getValue();
            parts.put(entry.getKey(), counts[1] + "/" + counts[0] + " loaded");
        }
        return parts.toString();
    }

    private static String bucketOf(String className) {
        if (AccessTransformerRules.isDatagenClass(className)) {
            return "datagen";
        }
        if (className.startsWith("net.minecraft.")) {
            return "vanilla";
        }
        if (className.startsWith("net.neoforged.") || className.startsWith("cpw.mods.")) {
            return "neoforge";
        }
        return "other";
    }

    private static String percent(int part, int whole) {
        if (whole == 0) {
            return "n/a";
        }
        return String.format(java.util.Locale.ROOT, "%.0f%% still unloaded",
                part * 100.0 / whole);
    }

    private static Set<String> loadedClassNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            Instrumentation instrumentation = JvmAccess.get();
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                names.add(type.getName());
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[AtSpike] cannot list loaded classes", t);
        }
        return new HashSet<>(names);
    }

    private static AbstractButton findButton(net.minecraft.client.gui.screens.Screen screen, String key) {
        String label = net.minecraft.client.resources.language.I18n.get(key);
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }
}
