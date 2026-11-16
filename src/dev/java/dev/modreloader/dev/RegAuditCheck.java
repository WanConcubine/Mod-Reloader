package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.inspect.RegistrationAudit;
import dev.modreloader.inspect.RegistrationAuditScanner;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dev-only harness for the registration audit
 * ({@code docs/features/client-registration-audit.md}).
 *
 * <p>Two things are checked, both without touching the running game:
 *
 * <ol>
 *   <li><b>Corpus scan</b>: every jar given on the command line is scanned and
 *       graded, one log line per jar. This is the data that tells us which
 *       startup-only event to replay next; it never hot-adds anything, so jars
 *       with access transformers or a broken dependency can be included.</li>
 *   <li><b>Fatal path</b>: a synthetic mod jar whose class references
 *       {@code RegisterParticleProvidersEvent} must be refused by
 *       {@link HotAddEngine} with {@link HotAddEngine.FailureKind#CLIENT_REGISTRATION}
 *       before anything is injected. A second jar references only an event we do
 *       not replay yet ({@code RegisterItemDecorationsEvent}, a "silent" gap) and
 *       must <em>not</em> be graded fatal; it is never added, only graded.
 *       ({@code RegisterGuiLayersEvent} was that exemplar until 2026-11-16, when
 *       the HUD-layer replay made it "covered".)</li>
 * </ol>
 *
 * <p>{@code -PverifyRegAudit=<jar>[,<jar>...]}.
 */
public final class RegAuditCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final String CRASH_EVENT =
            "net/neoforged/neoforge/client/event/RegisterParticleProvidersEvent";
    private static final String SILENT_EVENT =
            "net/neoforged/neoforge/client/event/RegisterItemDecorationsEvent";

    private static final List<Path> JARS = new ArrayList<>();
    private static int ticks;
    private static boolean started;
    private static boolean done;

    private RegAuditCheck() {
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
            ModReloader.LOGGER.warn("[RegAudit] no jars given; not starting");
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[RegAudit] corpus: {}", JARS);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started || done) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (++ticks < TITLE_SETTLE_TICKS || minecraft.getOverlay() != null) {
            return;
        }
        done = true;
        boolean pass = true;
        for (Path jar : JARS) {
            try {
                pass &= scan(jar);
            } catch (Throwable t) {
                ModReloader.LOGGER.error("[RegAudit] cannot scan {}", jar.getFileName(), t);
                pass = false;
            }
        }
        try {
            pass &= fatalPath();
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[RegAudit] fatal-path fixture failed", t);
            pass = false;
        }
        ModReloader.LOGGER.info("[RegAudit] RESULT: {}", pass ? "PASS" : "FAIL");
        started = false;
        minecraft.stop();
    }

    /** Read-only: grade every event the jar registers against. */
    private static boolean scan(Path jar) {
        RegistrationAudit.Report report = RegistrationAuditScanner.audit(jar, true);
        ModReloader.LOGGER.info("[RegAudit] {}", jar.getFileName());
        ModReloader.LOGGER.info("[RegAudit]   {} mod-bus event(s): {}", jar.getFileName(),
                report.entries().size());
        for (RegistrationAudit.Severity severity : RegistrationAudit.Severity.values()) {
            List<String> names = report.events(severity);
            if (!names.isEmpty()) {
                ModReloader.LOGGER.info("[RegAudit]   {} {} = {}",
                        jar.getFileName(), severity.label(), names);
            }
        }
        if (!report.unresolved().isEmpty()) {
            ModReloader.LOGGER.info("[RegAudit]   {} unresolved = {}",
                    jar.getFileName(), report.unresolved());
        }
        // A jar with no reference at all is a valid (if uninteresting) result.
        return true;
    }

    /** The audit must be able to stop a mod before a single class is injected. */
    private static boolean fatalPath() throws IOException {
        Path dir = Files.createTempDirectory("modreloader-regaudit");
        Path crashJar = dir.resolve("regaudit-crash-1.0.0.jar");
        writeFixtureJar(crashJar, "regauditcrash", CRASH_EVENT);
        RegistrationAudit.Report crashReport = RegistrationAuditScanner.audit(crashJar, true);
        ModReloader.LOGGER.info("[RegAudit] synthetic crash jar: {}", crashReport.line());
        boolean gradedFatal = crashReport.fatal()
                && crashReport.crashEvents().contains(
                        CRASH_EVENT.replace('/', '.'));

        HotAddEngine.Result result = HotAddEngine.add(crashJar);
        boolean refused = !result.ok()
                && result.kind() == HotAddEngine.FailureKind.CLIENT_REGISTRATION
                && result.stage() == HotAddEngine.Stage.VALIDATE;
        ModReloader.LOGGER.info(
                "[RegAudit] engine on crash jar: ok={} stage={} kind={} msg={}",
                result.ok(), result.stage(), result.kind(), result.message());

        Path silentJar = dir.resolve("regaudit-silent-1.0.0.jar");
        writeFixtureJar(silentJar, "regauditsilent", SILENT_EVENT);
        RegistrationAudit.Report silentReport = RegistrationAuditScanner.audit(silentJar, true);
        ModReloader.LOGGER.info("[RegAudit] synthetic silent jar: {}", silentReport.line());
        boolean silentNotFatal = !silentReport.fatal()
                && silentReport.events(RegistrationAudit.Severity.SILENT)
                        .contains(SILENT_EVENT.replace('/', '.'));

        boolean pass = gradedFatal && refused && silentNotFatal;
        ModReloader.LOGGER.info(
                "[RegAudit] fatal path graded={} refused={} silentNotFatal={} -> {}",
                gradedFatal, refused, silentNotFatal, pass ? "PASS" : "FAIL");
        return pass;
    }

    /**
     * A minimal but real NeoForge mod jar: valid {@code neoforge.mods.toml} plus
     * one class whose constant pool references the event. A field is enough for
     * the audit and keeps the class file trivial.
     */
    private static void writeFixtureJar(Path jar, String modId, String eventInternalName)
            throws IOException {
        String toml = """
                modLoader = "javafml"
                loaderVersion = "[4,)"
                license = "MIT"

                [[mods]]
                modId = "%s"
                version = "1.0.0"
                displayName = "Registration audit fixture"
                """.formatted(modId);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "dev/modreloader/dev/fixture/EventUser", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "event",
                "L" + eventInternalName + ";", null, null).visitEnd();
        writer.visitEnd();

        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(out, "META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8));
            add(out, "dev/modreloader/dev/fixture/EventUser.class", writer.toByteArray());
        }
    }

    private static void add(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
