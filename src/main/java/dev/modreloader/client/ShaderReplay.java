package dev.modreloader.client;

import com.mojang.datafixers.util.Pair;
import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import dev.modreloader.hotadd.OneShotReplay;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Replays {@code RegisterShadersEvent} for a hot-added mod after its resource
 * pack is loaded.
 *
 * <p>NeoForge fires the event inside {@code GameRenderer.reloadShaders}: the mod
 * constructs its {@link ShaderInstance}s against the current
 * {@code ResourceProvider}; after the event, the renderer puts each shader into
 * its private map and runs the consumer that assigns the mod's static field.
 * Shader JSONs live in the mod's resource pack, so this must run after the
 * hot-add reload, not during client registration replay.
 */
public final class ShaderReplay {
    private ShaderReplay() {
    }

    public static void replay(ModContainer container) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return;
        }
        List<Pair<ShaderInstance, Consumer<ShaderInstance>>> shaders = new ArrayList<>();
        try {
            OneShotReplay.fire(container, ReplayCatalog.REGISTER_SHADERS,
                    new RegisterShadersEvent(minecraft.getResourceManager(), shaders));
        } catch (Throwable t) {
            close(shaders);
            ModReloader.LOGGER.warn("[ModReloader] shader registration replay failed", t);
            return;
        }
        if (shaders.isEmpty()) {
            ModReloader.LOGGER.info("[ModReloader] shaders replayed for {}: none", container.getModId());
            return;
        }
        GameRenderer renderer = minecraft.gameRenderer;
        Map<String, ShaderInstance> live = UnsafeAccess.get(renderer, GameRenderer.class, "shaders");
        if (live == null) {
            close(shaders);
            ModReloader.LOGGER.warn("[ModReloader] shader map unavailable; {} shader(s) not applied",
                    shaders.size());
            return;
        }
        int added = 0;
        for (Pair<ShaderInstance, Consumer<ShaderInstance>> pair : shaders) {
            ShaderInstance shader = pair.getFirst();
            try {
                live.put(shader.getName(), shader);
                pair.getSecond().accept(shader);
                added++;
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot apply shader {}", shader.getName(), t);
                try {
                    shader.close();
                } catch (Throwable ignored) {
                    // Best effort; a broken shader must not fail the whole add.
                }
            }
        }
        ModReloader.LOGGER.info("[ModReloader] shaders replayed for {}: {} new, {} total",
                container.getModId(), added, live.size());
    }

    private static void close(List<Pair<ShaderInstance, Consumer<ShaderInstance>>> shaders) {
        for (Pair<ShaderInstance, Consumer<ShaderInstance>> pair : shaders) {
            try {
                pair.getFirst().close();
            } catch (Throwable ignored) {
                // Nothing useful to do while unwinding a failed registration.
            }
        }
    }
}
