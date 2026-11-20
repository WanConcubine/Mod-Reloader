package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.reflect.UnsafeAccess;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Repairs Sodium's fields on objects that were created before the hot-add.
 *
 * <p>A mixin that adds a field to an instance class leaves every existing
 * instance at the JVM default; Sodium reads several of them on the next tick
 * (issue 61). Two kinds are repairable without knowing the mod's internals:
 *
 * <ul>
 *   <li>{@code SpriteContents$Ticker.parent} - the owning {@link net.minecraft.client.renderer.texture.SpriteContents}
 *       is reachable from the atlas's ticker wrapper;</li>
 *   <li>{@code SpriteContents$InterpolationData.parent} - same owner;</li>
 *   <li>{@code BlockEntityType.sodium$renderPredicates} - a null array is
 *       meaningful to Sodium's render checks, so existing types get an empty one.</li>
 * </ul>
 *
 * <p>Version-pinned and opt-in with the rest of {@link SodiumBootstrap}; anything
 * not found is skipped (the add stays J2).
 */
final class SodiumBackfill {
    private SodiumBackfill() {
    }

    static void backfill(ClassLoader loader) {
        backfillTextureTickers(loader);
        backfillBlockEntityTypes(loader);
        backfillLevelRenderer(loader);
    }

    /**
     * {@code LevelRenderer.renderer} is created in its constructor; the live
     * renderer never runs that again, and the first resource reload calls
     * {@code renderer.reload()} from {@code allChanged()} (measured NPE).
     * {@code cloudRenderer} is lazy and may stay null.
     */
    private static void backfillLevelRenderer(ClassLoader loader) {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object mc = minecraft.getMethod("getInstance").invoke(null);
            Object levelRenderer = UnsafeAccess.get(mc, minecraft, "levelRenderer");
            if (levelRenderer == null) {
                return;
            }
            Class<?> rendererType = Class.forName(
                    "net.minecraft.client.renderer.LevelRenderer", false, loader);
            java.lang.reflect.Field field = rendererType.getDeclaredField("renderer");
            if (UnsafeAccess.get(levelRenderer, rendererType, field.getName()) != null) {
                return;
            }
            Class<?> worldRenderer = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", false, loader);
            // setLevel allocates GL resources; Sodium's own mixin wraps the same
            // call in RenderDevice.enterManagedCode/exitManagedCode.
            Class<?> device = Class.forName(
                    "net.caffeinemc.mods.sodium.client.gl.device.RenderDevice", false, loader);
            Object renderer;
            device.getMethod("enterManagedCode").invoke(null);
            try {
                renderer = worldRenderer.getConstructor(minecraft).newInstance(mc);
                UnsafeAccess.set(levelRenderer, rendererType, field.getName(), renderer);
                Object level = UnsafeAccess.get(mc, minecraft, "level");
                if (level != null) {
                    Class<?> levelType = Class.forName(
                            "net.minecraft.client.multiplayer.ClientLevel", false, loader);
                    worldRenderer.getMethod("setLevel", levelType).invoke(renderer, level);
                }
            } finally {
                device.getMethod("exitManagedCode").invoke(null);
            }
            ModReloader.LOGGER.info(
                    "[ModReloader] Sodium backfill: created the world renderer on the live LevelRenderer");
        } catch (NoSuchFieldException e) {
            // This add did not add the field.
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] Sodium backfill: level renderer skipped", t);
        }
    }

    private static void backfillTextureTickers(ClassLoader loader) {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object mc = minecraft.getMethod("getInstance").invoke(null);
            Object textureManager = minecraft.getMethod("getTextureManager").invoke(mc);
            Map<?, ?> byPath = UnsafeAccess.get(textureManager,
                    Class.forName("net.minecraft.client.renderer.texture.TextureManager", false, loader),
                    "byPath");
            if (byPath == null) {
                return;
            }
            Class<?> atlasType = Class.forName(
                    "net.minecraft.client.renderer.texture.TextureAtlas", false, loader);
            int repaired = 0;
            for (Object texture : byPath.values()) {
                if (!atlasType.isInstance(texture)) {
                    continue;
                }
                repaired += repairAtlas(texture, atlasType, loader);
            }
            ModReloader.LOGGER.info(
                    "[ModReloader] Sodium backfill: initialized parent on {} old sprite ticker(s)",
                    repaired);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] Sodium backfill: texture tickers skipped", t);
        }
    }

    private static int repairAtlas(Object atlas, Class<?> atlasType, ClassLoader loader)
            throws ReflectiveOperationException {
        if (!(UnsafeAccess.get(atlas, atlasType, "animatedTextures") instanceof List<?> tickers)) {
            return 0;
        }
        Class<?> spriteContents = Class.forName(
                "net.minecraft.client.renderer.texture.SpriteContents", false, loader);
        Class<?> tickerType = Class.forName(
                "net.minecraft.client.renderer.texture.SpriteContents$Ticker", false, loader);
        Class<?> interpolationType = Class.forName(
                "net.minecraft.client.renderer.texture.SpriteContents$InterpolationData",
                false, loader);
        int repaired = 0;
        for (Object wrapper : tickers) {
            if (wrapper == null) {
                continue;
            }
            try {
                Object sprite = UnsafeAccess.get(wrapper, wrapper.getClass(), "this$0");
                Object ticker = UnsafeAccess.get(wrapper, wrapper.getClass(), "val$spriteticker");
                Object contents = sprite.getClass().getMethod("contents").invoke(sprite);
                if (contents == null || ticker == null) {
                    continue;
                }
                if (UnsafeAccess.get(ticker, tickerType, "parent") == null) {
                    UnsafeAccess.set(ticker, tickerType, "parent", contents);
                    repaired++;
                }
                Object interpolation = UnsafeAccess.get(ticker, tickerType, "interpolationData");
                if (interpolation != null && interpolationType.isInstance(interpolation)
                        && UnsafeAccess.get(interpolation, interpolationType, "parent") == null) {
                    UnsafeAccess.set(interpolation, interpolationType, "parent", contents);
                }
                if (!spriteContents.isInstance(contents)) {
                    ModReloader.LOGGER.debug(
                            "[ModReloader] Sodium backfill: unexpected sprite contents {}",
                            contents.getClass().getName());
                }
            } catch (IllegalStateException ignored) {
                // Sodium or vanilla changed the wrapper shape; skip this entry.
            }
        }
        return repaired;
    }

    private static void backfillBlockEntityTypes(ClassLoader loader) {
        try {
            Class<?> builtIns = Class.forName("net.minecraft.core.registries.BuiltInRegistries",
                    false, loader);
            Object registry = builtIns.getField("BLOCK_ENTITY_TYPE").get(null);
            Class<?> typeType = Class.forName(
                    "net.minecraft.world.level.block.entity.BlockEntityType", false, loader);
            int repaired = 0;
            for (Object type : (Iterable<?>) registry) {
                if (!typeType.isInstance(type)) {
                    continue;
                }
                try {
                    Field field = typeType.getDeclaredField("sodium$renderPredicates");
                    if (UnsafeAccess.get(type, typeType, field.getName()) == null) {
                        Object empty = java.lang.reflect.Array.newInstance(
                                field.getType().getComponentType(), 0);
                        UnsafeAccess.set(type, typeType, field.getName(), empty);
                        repaired++;
                    }
                } catch (NoSuchFieldException ignored) {
                    // This add did not add the field to BlockEntityType.
                    return;
                }
            }
            if (repaired > 0) {
                ModReloader.LOGGER.info(
                        "[ModReloader] Sodium backfill: empty render-predicate arrays on {} "
                                + "existing block entity type(s)", repaired);
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] Sodium backfill: block entity types skipped", t);
        }
    }
}