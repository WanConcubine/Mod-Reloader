package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.launch.Relauncher;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dev-only check for managed relaunch <em>without</em> restarting the game.
 *
 * <p>Two things are verified:
 *
 * <ol>
 *   <li>the plan for the <em>real</em> command line can be built and quoted (script
 *       written under {@code <gamedir>/modreloader/relaunch/}, content logged);</li>
 *   <li>the same script + spawn path actually starts a JVM: a harmless
 *       {@code java -version} script is spawned the way a relaunch would be, and
 *       its exit status plus captured output are asserted.</li>
 * </ol>
 *
 * <p>It deliberately does not spawn a second game window. {@code -PverifyRelaunchPlan=true}.
 */
public final class RelaunchCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int TIMEOUT_TICKS = 20 * 120;

    private static int ticks;
    private static boolean done;

    private RelaunchCheck() {
    }

    public static void start() {
        ModReloader.LOGGER.info("[RelaunchCheck] managed-relaunch plan check");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (done) {
            return;
        }
        if (++ticks < TITLE_SETTLE_TICKS) {
            return;
        }
        done = true;
        check();
    }

    private static void check() {
        List<String> problems = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft.gameDirectory.toPath();
        ModReloader.LOGGER.info("[RelaunchCheck] canRelaunch={} blocker={}",
                Relauncher.canRelaunch(), Relauncher.blocker());

        // 1. The real plan: same JVM, same command line, plus the relaunch markers.
        String[] raw = ProcessHandle.current().info().arguments().orElse(new String[0]);
        List<String> realArguments = Relauncher.stripRelaunchFlags(raw);
        realArguments.add("-D" + Relauncher.RELAUNCHED + "=true");
        ModReloader.LOGGER.info("[RelaunchCheck] parent command line: {} arg(s), executable {}",
                realArguments.size(), Relauncher.executable());
        try {
            Path script = Relauncher.writeScript(gameDir, Relauncher.executable(), realArguments, 2);
            String text = Files.readString(script, StandardCharsets.UTF_8);
            ModReloader.LOGGER.info("[RelaunchCheck] plan script {} ({} chars): {}", script,
                    text.length(), text.lines().limit(3).toList());
            if (!text.contains(Relauncher.executable().toString())) {
                problems.add("plan script does not reference the current java executable");
            }
        } catch (Throwable t) {
            problems.add("cannot write the plan script: " + t);
        }

        // 2. The spawn path, with a harmless command.
        try {
            Path script = Relauncher.writeScript(gameDir, Relauncher.executable(),
                    List.of("-version"), 0);
            Process child = Relauncher.spawn(script, gameDir);
            boolean exited = child.waitFor(20, TimeUnit.SECONDS);
            String log = Files.readString(script.resolveSibling(script.getFileName() + ".log"),
                    StandardCharsets.UTF_8);
            ModReloader.LOGGER.info("[RelaunchCheck] test spawn exited={} code={} log={}",
                    exited, exited ? child.exitValue() : "?", log.lines().findFirst().orElse(""));
            if (!exited) {
                problems.add("the spawned script did not exit within 20s");
            } else if (child.exitValue() != 0) {
                problems.add("the spawned script exited with " + child.exitValue());
            }
            if (!log.toLowerCase(java.util.Locale.ROOT).contains("version")) {
                problems.add("the spawned JVM produced no version output");
            }
        } catch (Throwable t) {
            problems.add("spawn test threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // 3. B: the JBR executable + enhanced-redefinition flag must be usable too.
        try {
            var jbr = Relauncher.jbrExecutable();
            ModReloader.LOGGER.info("[RelaunchCheck] JBR install={} jbrExecutable={} jbrAvailable={}",
                    dev.modreloader.inspect.JbrSupport.installation(), jbr,
                    Relauncher.jbrRelaunchAvailable());
            if (jbr.isPresent()) {
                java.util.List<String> args = new ArrayList<>(
                        java.util.List.of("-XX:+AllowEnhancedClassRedefinition", "-version"));
                Path script = Relauncher.writeScript(gameDir, jbr.get(), args, 0);
                Process child = Relauncher.spawn(script, gameDir);
                boolean exited = child.waitFor(20, TimeUnit.SECONDS);
                String log = Files.readString(script.resolveSibling(script.getFileName() + ".log"),
                        StandardCharsets.UTF_8);
                ModReloader.LOGGER.info("[RelaunchCheck] JBR spawn exited={} code={} log={}",
                        exited, exited ? child.exitValue() : "?",
                        log.lines().findFirst().orElse(""));
                if (!exited || child.exitValue() != 0) {
                    problems.add("the JBR replacement path did not exit cleanly");
                }
                if (!log.toLowerCase(java.util.Locale.ROOT).contains("version")) {
                    problems.add("the JBR replacement produced no version output");
                }
            } else {
                ModReloader.LOGGER.info("[RelaunchCheck] no JBR install on this machine; B path not exercised");
            }
        } catch (Throwable t) {
            problems.add("JBR spawn test threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        if (problems.isEmpty()) {
            ModReloader.LOGGER.info("[RelaunchCheck] RESULT: PASS - plan + spawn path work; "
                    + "the real relaunch itself is left to the user (it would exit this client)");
        } else {
            for (String problem : problems) {
                ModReloader.LOGGER.error("[RelaunchCheck] problem: {}", problem);
            }
            ModReloader.LOGGER.error("[RelaunchCheck] RESULT: FAIL - {}", problems);
        }
        ModReloader.LOGGER.info("[RelaunchCheck] stopping client");
        minecraft.stop();
    }
}
