package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-opens XaeroLib's one-shot registries for one hot-add.
 *
 * <p>Startup freezes these singletons once every Xaero mod has registered:
 *
 * <ul>
 * <li>{@code xaero.lib.common.config.channel.register.ConfigChannelRegistry} -
 *     {@code register(...)} throws {@code IllegalStateException: Attempted to
 *     register a config channel ... too late};</li>
 * <li>{@code xaero.lib.common.permission.PermissionRegistry} - same {@code frozen}
 *     flag, and a hot-added Xaero mod registers built-in channel permissions
 *     during its own construction (measured: World Map first, then Minimap ->
 *     {@code IllegalStateException} from {@code BuiltInConfigChannelPermissions}).</li>
 * </ul>
 *
 * <p>The window is targeted: it only runs for jars that reference one of those
 * classes, so no other mod's state is touched. It is also version sensitive (the
 * field name), which is why this lives next to the other capability anchors and
 * logs loudly instead of failing the add.
 */
public final class XaeroLibChannels {
    /** Registries with a private {@code boolean frozen} and a public INSTANCE. */
    private static final String[] REGISTRIES = {
            "xaero.lib.common.config.channel.register.ConfigChannelRegistry",
            "xaero.lib.common.permission.PermissionRegistry",
            "xaero.lib.common.permission.system.PermissionSystemRegistry",
            "xaero.lib.common.packet.PacketHandlerRegistry",
    };
    private static final String[] TARGETS = {
            "xaero/lib/common/config/channel/register/ConfigChannelRegistry",
            "xaero/lib/common/permission/PermissionRegistry",
            "xaero/lib/common/permission/system/PermissionSystemRegistry",
            "xaero/lib/common/packet/PacketHandlerRegistry",
    };

    private record Opened(String name, Class<?> type, Object instance) {
    }

    private static final List<Opened> opened = new ArrayList<>();

    private XaeroLibChannels() {
    }

    /** True when the jar (its own classes) references one of the guarded registries. */
    public static boolean appliesTo(Path jar) {
        try {
            java.util.Set<String> referenced =
                    dev.modreloader.inspect.RegistrationAuditScanner.referencedTypes(jar);
            for (String target : TARGETS) {
                if (referenced.contains(target)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens the registration window; must be paired with {@link #close()}. */
    public static void open(Rollback rollback) {
        if (!opened.isEmpty()) {
            return;
        }
        List<Opened> freezeAgain = new ArrayList<>();
        try {
            for (String name : REGISTRIES) {
                Class<?> type;
                try {
                    type = Class.forName(name, false, XaeroLibChannels.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    // The jar references the class but it is not on this side (e.g. a
                    // client-only mod on a server); nothing to guard.
                    continue;
                }
                Object instance = UnsafeAccess.getStatic(type, "INSTANCE");
                if (instance == null) {
                    continue;
                }
                boolean frozen = UnsafeAccess.getBoolean(instance, type, "frozen");
                if (!frozen) {
                    ModReloader.LOGGER.info(
                            "[ModReloader] {} is already open; not guarding", name);
                    continue;
                }
                UnsafeAccess.setBoolean(instance, type, "frozen", false);
                freezeAgain.add(new Opened(name, type, instance));
                ModReloader.LOGGER.info(
                        "[ModReloader] XaeroLib window open: {} (frozen -> false)", name);
            }
            if (!freezeAgain.isEmpty()) {
                opened.addAll(freezeAgain);
                rollback.add("xaero registries", XaeroLibChannels::restore);
            }
        } catch (Throwable t) {
            // Undo a partial open; the add continues and may still succeed if the
            // mod does not touch the registry we could not open.
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot open the XaeroLib registration window (version "
                            + "mismatch?); the add may fail with 'too late'", t);
            opened.addAll(freezeAgain);
            restore();
        }
    }

    /** Restores the freeze state after the add; safe to call repeatedly. */
    public static void close() {
        restore();
    }

    private static void restore() {
        for (Opened entry : opened) {
            try {
                UnsafeAccess.setBoolean(entry.instance(), entry.type(), "frozen", true);
                ModReloader.LOGGER.info(
                        "[ModReloader] XaeroLib window closed: {} (frozen -> true)", entry.name());
            } catch (Throwable t) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] cannot restore the freeze state of {}", entry.name(), t);
            }
        }
        opened.clear();
    }
}