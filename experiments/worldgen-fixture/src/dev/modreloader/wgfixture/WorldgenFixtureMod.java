package dev.modreloader.wgfixture;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worldgen fixture: no code content, one custom dimension shipped as data
 * ({@code data/wgfixture/dimension/wgflat.json}).
 *
 * <p>Used to answer one question: a title-screen hot-add puts the jar's data pack
 * into the client/server pack set — can a dimension from it be used by a world
 * that is created <em>after</em> the add? Dimensions are WORLDGEN registry content
 * and are built during world load, so the answer decided whether the documented
 * boundary is "needs a restart" or "needs a new world".
 */
@Mod(WorldgenFixtureMod.MOD_ID)
public final class WorldgenFixtureMod {
    public static final String MOD_ID = "wgfixture";
    private static final Logger LOGGER = LoggerFactory.getLogger("WgFixture");

    public WorldgenFixtureMod(IEventBus modBus) {
        LOGGER.info("[WgFixture] constructed; dimension wgfixture:wgflat is data-only");
    }
}
