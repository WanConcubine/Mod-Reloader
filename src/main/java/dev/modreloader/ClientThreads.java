package dev.modreloader;

import net.minecraft.client.Minecraft;

/** Client-only thread hop, isolated so {@link Platform} stays server-safe. */
final class ClientThreads {
    private ClientThreads() {
    }

    static void run(Runnable task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            task.run();
            return;
        }
        minecraft.execute(task);
    }
}
