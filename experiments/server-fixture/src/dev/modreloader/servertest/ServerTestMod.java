package dev.modreloader.servertest;

import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal server-safe fixture: one @Mod class, no client classes, no registries,
 * no mixins, no payloads. Used to prove the server-side hot-add success path.
 */
@Mod(ServerTestMod.MOD_ID)
public final class ServerTestMod {
    public static final String MOD_ID = "servertest";
    private static final Logger LOGGER = LoggerFactory.getLogger("ServerTest");

    public ServerTestMod() {
        LOGGER.info("[ServerTest] @Mod constructed; modListIsLoaded={}; side={}",
                ModList.get().isLoaded(MOD_ID),
                System.getProperty("os.name"));
    }
}
