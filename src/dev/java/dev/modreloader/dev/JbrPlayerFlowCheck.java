package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.client.JbrProvisioner;
import dev.modreloader.client.PendingAdd;
import dev.modreloader.client.Relauncher;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.inspect.ModInspector;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * End-to-end check of the player-facing JBR path, split across two JVMs.
 *
 * <p>Phase 1 runs on a stock JVM: it pretends no JBR is installed, verifies the
 * first jar cannot be hot-added, provisions JBR (real download, SHA-512, unpack),
 * finds it again, writes the pending-add hint and relaunches on it. Phase 2 runs
 * in the replacement JBR process and hot-adds the queued jars, writing a result
 * file the caller can read.
 *
 * <p>Dev-only: {@code -PverifyJbrFlow=<jar>[,<jar>...]}. Phase is decided by the
 * running JVM, so the relaunched process does not need an extra switch.
 */
public final class JbrPlayerFlowCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final String JARS_FILE = "jbr-flow-jars.txt";
    private static final String PHASE1_FILE = "jbr-flow-phase1.txt";
    private static final String RESULT_FILE = "jbr-flow-result.txt";

    private static List<Path> jars = List.of();
    private static int ticks;
    private static boolean started;

    private JbrPlayerFlowCheck() {
    }

    public static void start(String csv) {
        List<Path> parsed = new ArrayList<>();
        for (String part : csv.split(",")) {
            String raw = part.trim();
            if (!raw.isEmpty()) {
                parsed.add(Path.of(raw).toAbsolutePath().normalize());
            }
        }
        jars = List.copyOf(parsed);
        if (jars.isEmpty()) {
            ModReloader.LOGGER.warn("[JbrFlow] no jars given; not starting");
            return;
        }
        ModReloader.LOGGER.info("[JbrFlow] queue: {}", jars);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (started || ++ticks < TITLE_SETTLE_TICKS) {
            return;
        }
        started = true;
        if (JbrSupport.available()) {
            phase2();
        } else {
            phase1();
        }
    }

    private static void phase1() {
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft.gameDirectory.toPath();
        Thread worker = new Thread(() -> {
            List<String> out = new ArrayList<>();
            out.add("PHASE1 vendor=" + System.getProperty("java.vendor")
                    + " enhanced=" + JbrSupport.enhancedRedefinition());
            String originalHome = System.getProperty("user.home", "");
            Path provisioned;
            try {
                // Pretend the machine has no JBR: only the game dir/ancestors are
                // searched, like a fresh player install.
                System.setProperty("user.home", gameDir.resolve("no-installed-jbr").toString());
                out.add("PHASE1 installationBefore=" + JbrSupport.installation());
                HotAddEngine.Result stock = HotAddEngine.add(jars.get(0));
                out.add("PHASE1 stockAdd ok=" + stock.ok() + " stage=" + stock.stage()
                        + " kind=" + stock.kind());
                provisioned = JbrProvisioner.install(gameDir,
                        message -> ModReloader.LOGGER.info("[JbrFlow] provision: {}", message));
                out.add("PHASE1 provisioned=" + provisioned);
            } catch (Throwable t) {
                out.add("PHASE1 provision failed: " + t.getClass().getSimpleName() + ": "
                        + t.getMessage());
                write(gameDir, PHASE1_FILE, out);
                Platform.runOnMainThread(() -> stop());
                return;
            } finally {
                System.setProperty("user.home", originalHome);
            }

            Optional<Path> found = JbrSupport.installation();
            boolean foundProvisioned = found.isPresent()
                    && found.get().toAbsolutePath().normalize()
                    .equals(provisioned.toAbsolutePath().normalize());
            out.add("PHASE1 installationAfter=" + found + " foundProvisioned=" + foundProvisioned);
            if (!foundProvisioned) {
                out.add("PHASE1 FAIL: installation() did not find the provisioned runtime");
                write(gameDir, PHASE1_FILE, out);
                Platform.runOnMainThread(() -> stop());
                return;
            }
            Path java = provisioned.resolve("bin").resolve(Relauncher.isWindows() ? "java.exe" : "java");
            write(gameDir, JARS_FILE, jars.stream().map(Path::toString).toList());
            PendingAdd.write(gameDir, jars.get(0));
            // saveAndRestart exits this JVM on success, so write the evidence first.
            out.add("PHASE1 relaunch=attempting");
            write(gameDir, PHASE1_FILE, out);
            String failure = Relauncher.saveAndRestart(minecraft, "verify-jbr-flow", java,
                    Relauncher.jbrFlags(true)).orElse(null);
            if (failure != null) {
                out.add("PHASE1 relaunch=FAILED: " + failure);
                write(gameDir, PHASE1_FILE, out);
                Platform.runOnMainThread(() -> stop());
            }
        }, "modreloader-jbr-flow");
        worker.setDaemon(true);
        worker.start();
    }

    private static void phase2() {
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft.gameDirectory.toPath();
        List<Path> queue = readJars(gameDir);
        List<String> out = new ArrayList<>();
        out.add("PHASE2 jvm=" + System.getProperty("java.vendor") + " "
                + System.getProperty("java.version") + " " + JbrSupport.describe());
        boolean all = !queue.isEmpty();
        for (Path jar : queue) {
            String modId = primaryModId(jar);
            HotAddEngine.Result result = HotAddEngine.add(jar);
            boolean loaded = ModList.get() != null && !modId.isEmpty()
                    && ModList.get().isLoaded(modId);
            out.add("PHASE2 RESULT " + jar.getFileName() + " modId=" + modId
                    + " ok=" + result.ok() + " stage=" + result.stage()
                    + " kind=" + result.kind() + " isLoaded=" + loaded);
            all &= result.ok() && loaded;
        }
        out.add("RESULT: " + (all ? "PASS" : "FAIL"));
        write(gameDir, RESULT_FILE, out);
        ModReloader.LOGGER.info("[JbrFlow] {}", out.get(out.size() - 1));
        minecraft.stop();
    }

    private static String primaryModId(Path jar) {
        try {
            ModInspection inspection = ModInspector.inspect(jar);
            if (inspection.ok() && !inspection.mods().isEmpty()) {
                return inspection.mods().get(0).modId();
            }
        } catch (Throwable ignored) {
            // Fall through: the result line records the empty id.
        }
        return "";
    }

    private static List<Path> readJars(Path gameDir) {
        try {
            Path file = gameDir.resolve("modreloader").resolve(JARS_FILE);
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            List<Path> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    result.add(Path.of(line.trim()).toAbsolutePath().normalize());
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            ModReloader.LOGGER.warn("[JbrFlow] cannot read {}", JARS_FILE, e);
            return List.of();
        }
    }

    private static void write(Path gameDir, String name, List<String> lines) {
        try {
            Path file = gameDir.resolve("modreloader").resolve(name);
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ModReloader.LOGGER.warn("[JbrFlow] cannot write {}", name, e);
        }
    }

    private static void stop() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.stop();
        }
    }
}
