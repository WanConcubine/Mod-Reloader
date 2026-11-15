package dev.modreloader;

import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Side helpers shared by both sides.
 *
 * <p>Kept free of client types on purpose: a dedicated server loads this class,
 * and anything it references must exist there too.
 */
public final class Platform {
    private Platform() {
    }

    public static boolean isClient() {
        return FMLEnvironment.dist.isClient();
    }

    public static String side() {
        return isClient() ? "client" : "dedicated server";
    }

    /**
     * Runs {@code task} on the thread that is allowed to touch game state: the
     * client render thread, or the calling thread on a server (no render thread).
     */
    public static void runOnMainThread(Runnable task) {
        if (isClient()) {
            ClientThreads.run(task);
        } else {
            task.run();
        }
    }
}
