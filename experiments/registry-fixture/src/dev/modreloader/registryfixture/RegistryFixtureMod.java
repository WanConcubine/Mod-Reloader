package dev.modreloader.registryfixture;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixture for a mod that creates its own built-in registry at construction time.
 *
 * <p>NeoForge only allows this before startup finishes
 * ({@code RegistryManager.pendingModdedRegistries} is null afterwards), so a hot-add
 * fails with "Attempting to instantiate registry … after NewRegistryEvent was fired!"
 * unless the runtime reopens that window and replays {@code NewRegistryEvent}.
 * The runtime asserts the registry and its entry exist after the add.
 *
 * <p>Used by {@code -PverifyCompat=<jar> -PverifyCustomRegistry=registryfixture:trades}
 * (see {@code docs/research-newregistry-hotadd.md}).
 */
@Mod(RegistryFixtureMod.MOD_ID)
public final class RegistryFixtureMod {
    public static final String MOD_ID = "registryfixture";
    public static final String REGISTRY_PATH = "trades";
    public static final ResourceKey<Registry<Trade>> TRADES_KEY = ResourceKey.createRegistryKey(
            ResourceLocation.fromNamespaceAndPath(MOD_ID, REGISTRY_PATH));
    private static final DeferredRegister<Trade> TRADES =
            DeferredRegister.create(TRADES_KEY, MOD_ID);
    private static final Logger LOGGER = LoggerFactory.getLogger("RegistryFixture");

    /** Plain value type: a built-in registry needs nothing else. */
    public record Trade(String name) {
    }

    public RegistryFixtureMod(IEventBus modBus) {
        // Must happen before RegisterEvent: the registry itself is created here,
        // and the DeferredRegister attaches the listeners for both events.
        TRADES.makeRegistry(builder -> builder.sync(true));
        TRADES.register(modBus);
        TRADES.register("test_trade", () -> new Trade("test"));
        LOGGER.info("[RegistryFixture] constructed; registry {} with one entry", TRADES_KEY.location());
    }
}