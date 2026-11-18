package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.nio.file.Path;

/**
 * Re-opens XaeroLib's config-channel registry for one hot-add.
 *
 * <p>{@code xaero.lib.common.config.channel.register.ConfigChannelRegistry} has a
 * {@code frozen} flag that is set once at startup; {@code register(...)} then
 * throws {@code IllegalStateException: Attempted to register a config channel ...
 * too late}. A hot-added Xaero mod (World Map, Minimap, XaeroPlus) registers its
 * channels during these startup events and would fail.
 *
 * <p>The window is targeted: it only runs for jars that reference that registry
 * class, so no other mod's state is touched. It is also version sensitive (the
 * field name), which is why this lives next to the other capability anchors and
 * logs loudly instead of failing the add.
 */
public final class XaeroLibChannels {
    private static final String REGISTRY =
            "xaero.lib.common.config.channel.register.ConfigChannelRegistry";
    private static final String TARGET =
            "xaero/lib/common/config/channel/register/ConfigChannelRegistry";

    private static boolean open;
    private static Class<?> registryType;
    private static Object registry;

    private XaeroLibChannels() {
    }

    /** True when the jar (its own classes) references XaeroLib's channel registry. */
    public static boolean appliesTo(Path jar) {
        try {
            return dev.modreloader.inspect.RegistrationAuditScanner.referencedTypes(jar)
                    .contains(TARGET);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens the registration window; must be paired with {@link #close()}. */
    public static void open(Rollback rollback) {
        if (open) {
            return;
        }
        try {
            Class<?> type = Class.forName(REGISTRY, false,
                    XaeroLibChannels.class.getClassLoader());
            Object instance = UnsafeAccess.getStatic(type, "INSTANCE");
            if (instance == null) {
                return;
            }
            boolean frozen = UnsafeAccess.getBoolean(instance, type, "frozen");
            if (!frozen) {
                ModReloader.LOGGER.info(
                        "[ModReloader] XaeroLib config channels are already open; not guarding");
                return;
            }
            UnsafeAccess.setBoolean(instance, type, "frozen", false);
            registryType = type;
            registry = instance;
            open = true;
            rollback.add("xaero config channels", XaeroLibChannels::restore);
            ModReloader.LOGGER.info(
                    "[ModReloader] XaeroLib config-channel window open (frozen -> false)");
        } catch (ClassNotFoundException e) {
            // The jar references the class but it is not on this side (e.g. a
            // client-only mod on a server); nothing to guard.
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot open the XaeroLib config-channel window (version "
                            + "mismatch?); the add may fail with 'too late'", t);
        }
    }

    /** Restores the freeze state after the add; safe to call repeatedly. */
    public static void close() {
        restore();
    }

    private static void restore() {
        if (!open) {
            return;
        }
        try {
            UnsafeAccess.setBoolean(registry, registryType, "frozen", true);
            ModReloader.LOGGER.info(
                    "[ModReloader] XaeroLib config-channel window closed (frozen -> true)");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot restore the XaeroLib channel freeze state", t);
        } finally {
            open = false;
            registry = null;
            registryType = null;
        }
    }
}