package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

/**
 * Replays the client-side registrations that NeoForge only performs once during
 * startup. A hot-added mod's {@code @EventBusSubscriber} is registered, but the
 * events already fired, so without this its renderers / tints / extensions are
 * silently missing (block entity contents do not draw, buckets stay white).
 *
 * <p>Each event has different duplicate semantics, so the strategy is per event:
 *
 * <ul>
 *   <li><b>renderers</b> ({@code EntityRenderersEvent.RegisterRenderers}): the
 *       handlers only {@code put} into the static provider maps, so a plain
 *       replay is safe. The actual renderer instances are built by the
 *       dispatchers' {@code onResourceManagerReload}, which must be re-run
 *       afterwards — the mod's provider would otherwise never be instantiated.</li>
 *   <li><b>color handlers</b>: also plain {@code put} into {@code ItemColors} /
 *       {@code BlockColors}, safe to replay as is.</li>
 *   <li><b>client extensions</b>: {@code ClientExtensionsManager.register} throws
 *       on a duplicate key, and the event writes into four static maps. Replay it
 *       against fresh throwaway maps, then merge the entries that are new.</li>
 *   <li><b>client reload listeners</b>: not replayed — the real
 *       {@code ReloadableResourceManager} would end up with duplicate listeners
 *       and no way to tell them apart (documented in known issue 21).</li>
 * </ul>
 *
 * <p>Model layers ({@code EntityRenderersEvent.RegisterLayerDefinitions}) are
 * baked at startup; a hot-added renderer that needs a new layer definition will
 * still fail. Machines/barrels/sieves render from existing models, so the
 * dogfood does not hit that.
 */
public final class ClientRegistrationReplay {
    private ClientRegistrationReplay() {
    }

    public static void replay(ModContainer container) {
        replayRenderers(container);
        replayColorHandlers(container);
        replayClientExtensions(container);
    }

    private static void replayRenderers(ModContainer container) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            container.acceptEvent(new EntityRenderersEvent.RegisterRenderers());
            // The providers are only templates; the dispatchers bake the real
            // renderers from them on a resource reload.
            minecraft.getBlockEntityRenderDispatcher()
                    .onResourceManagerReload(minecraft.getResourceManager());
            minecraft.getEntityRenderDispatcher()
                    .onResourceManagerReload(minecraft.getResourceManager());
            ModReloader.LOGGER.info(
                    "[ModReloader] renderers replayed; block entity renderers rebuilt");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] renderer replay failed", t);
        }
    }

    private static void replayColorHandlers(ModContainer container) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            container.acceptEvent(new RegisterColorHandlersEvent.Block(minecraft.getBlockColors()));
            container.acceptEvent(new RegisterColorHandlersEvent.Item(
                    minecraft.getItemColors(), minecraft.getBlockColors()));
            ModReloader.LOGGER.info("[ModReloader] color handlers replayed");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] color handler replay failed", t);
        }
    }

    /**
     * The four extension maps are static and the event writes into them directly,
     * so swap them for throwaway maps while the event runs, then merge the new
     * entries back. Other mods re-register their own entries into the throwaway
     * maps and those are discarded (their originals are untouched).
     */
    @SuppressWarnings("unchecked")
    private static void replayClientExtensions(ModContainer container) {
        String[] fields = {"BLOCK_EXTENSIONS", "ITEM_EXTENSIONS", "MOB_EFFECT_EXTENSIONS",
                "FLUID_TYPE_EXTENSIONS"};
        Map<Object, Object>[] originals = new Map[fields.length];
        Map<Object, Object>[] fresh = new Map[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) {
                originals[i] = UnsafeAccess.getStatic(ClientExtensionsManager.class, fields[i]);
                fresh[i] = new HashMap<>();
                UnsafeAccess.setStatic(ClientExtensionsManager.class, fields[i], fresh[i]);
            }
            RegisterClientExtensionsEvent event = (RegisterClientExtensionsEvent) UnsafeAccess.trusted()
                    .findConstructor(RegisterClientExtensionsEvent.class,
                            MethodType.methodType(void.class))
                    .invoke();
            container.acceptEvent(event);

            int added = 0;
            for (int i = 0; i < fields.length; i++) {
                for (Map.Entry<Object, Object> entry : fresh[i].entrySet()) {
                    if (originals[i].putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                        added++;
                    }
                }
            }
            ModReloader.LOGGER.info("[ModReloader] client extensions replayed: {} new", added);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] client extension replay failed", t);
        } finally {
            for (int i = 0; i < fields.length; i++) {
                if (originals[i] != null) {
                    UnsafeAccess.setStatic(ClientExtensionsManager.class, fields[i], originals[i]);
                }
            }
        }
    }
}
