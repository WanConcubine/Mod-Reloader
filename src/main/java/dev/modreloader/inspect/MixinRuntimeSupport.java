package dev.modreloader.inspect;

import net.neoforged.fml.ModList;

/**
 * Detection for the optional MixinRuntime dependency (modId {@code mr}).
 *
 * <p>This class deliberately touches no MixinRuntime types: the mod must load
 * <em>without</em> that dependency so we can tell the user what is missing
 * ourselves. FML's dependency check would otherwise abort the whole launch and
 * print the raw modId, which is exactly the confusing case we want to avoid.
 */
public final class MixinRuntimeSupport {
    /** The modId FML prints in its messages — not a name users recognize. */
    public static final String MOD_ID = "mr";
    public static final String DISPLAY_NAME = "MixinRuntime";

    private MixinRuntimeSupport() {
    }

    public static boolean isLoaded() {
        try {
            ModList list = ModList.get();
            return list != null && list.isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cheap class-presence probe; does not initialize the class. */
    public static boolean classPresent() {
        try {
            Class.forName("cn.ksmcbrigade.mr.MixinRuntimeMod", false,
                    MixinRuntimeSupport.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean available() {
        return isLoaded() && classPresent();
    }

    /** "MixinRuntime (mr)" — use this in user-facing text instead of the bare modId. */
    public static String name() {
        return DISPLAY_NAME + " (" + MOD_ID + ")";
    }

    /** Short state for detail lines that already print the name. */
    public static String describe() {
        return available() ? "present" : "missing (modId: " + MOD_ID + ")";
    }
}
