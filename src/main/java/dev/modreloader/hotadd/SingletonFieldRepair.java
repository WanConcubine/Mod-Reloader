package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Initializes the one singleton mixin field we know is read before anything can
 * write it: Sodium's {@code Minecraft.fences}.
 *
 * <p>A mixin that adds an instance field to a class that already has a live
 * instance leaves that instance at the JVM default: the mixed constructor never
 * runs again. Most such fields cause wrong values (Jade's font brightness); this
 * one is read on every tick, so the client NPEs and crashes before the user can
 * restart cleanly. Repairing it is best effort and version sensitive: the field
 * and its type must match, otherwise nothing is touched and the add stays J2.
 */
final class SingletonFieldRepair {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String FENCES_TYPE = "it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue";

    private SingletonFieldRepair() {
    }

    static void afterRedefine(String dotted, Class<?> type) {
        if (!MINECRAFT.equals(dotted)) {
            return;
        }
        if (!Boolean.parseBoolean(System.getProperty("modreloader.sodiumBootstrap", "false"))) {
            return;
        }
        try {
            type.getDeclaredField("fences");
        } catch (NoSuchFieldException e) {
            return; // this add did not add the field
        }
        try {
            Object instance = type.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return;
            }
            Object current = UnsafeAccess.get(instance, type, "fences");
            if (current != null) {
                return;
            }
            Class<?> queueType = Class.forName(FENCES_TYPE, false, type.getClassLoader());
            Object queue = queueType.getDeclaredConstructor().newInstance();
            UnsafeAccess.set(instance, type, "fences", queue);
            ModReloader.LOGGER.info(
                    "[ModReloader] initialized {}.fences on the live instance after the mixin "
                            + "redefine (layout change would otherwise NPE on the next tick)",
                    dotted);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot initialize {}.fences; the live instance keeps the JVM "
                            + "default and the client may crash until restart (J2)", dotted, t);
        }
    }
}