package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.client.renderer.ItemModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        registerItemModels(container);
        replayRenderers(container);
        replayColorHandlers(container);
        replayClientExtensions(container);
        replayClientReloadListeners(container);
    }

    /**
     * Client-side reload listeners (Ex Nihilo registers its machine tables with
     * {@code clientOnly = true} here so JEI and a dedicated-server client can see
     * them). Fired once at startup; replay into a throwaway resource manager and
     * copy over the listener classes the real manager does not have yet.
     *
     * <p>Deduplication is by listener class: a second registration of the same
     * class would run the reload twice for every pack reload, and the event
     * hands out fresh instances so identity comparison cannot tell them apart.
     * A mod that intentionally registers two differently-configured instances of
     * one class would lose the second one — accepted, and no known mod does it.
     */
    private static void replayClientReloadListeners(ModContainer container) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (!(minecraft.getResourceManager() instanceof ReloadableResourceManager real)) {
                return;
            }
            ReloadableResourceManager probe = new ReloadableResourceManager(PackType.CLIENT_RESOURCES);
            container.acceptEvent(new RegisterClientReloadListenersEvent(probe));
            List<PreparableReloadListener> fresh = UnsafeAccess.get(
                    probe, ReloadableResourceManager.class, "listeners");
            List<PreparableReloadListener> existing = UnsafeAccess.get(
                    real, ReloadableResourceManager.class, "listeners");
            Set<String> known = new HashSet<>();
            for (PreparableReloadListener listener : existing) {
                known.add(listener.getClass().getName());
            }
            int added = 0;
            for (PreparableReloadListener listener : fresh) {
                if (known.add(listener.getClass().getName())) {
                    real.registerReloadListener(listener);
                    added++;
                }
            }
            ModReloader.LOGGER.info(
                    "[ModReloader] client reload listeners replayed: {} new, {} total",
                    added, existing.size());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] client reload listener replay failed", t);
        }
    }

    /**
     * {@code ItemRenderer}'s constructor snapshots {@code BuiltInRegistries.ITEM}
     * into NeoForge's {@code RegistryAwareItemModelShaper}; items registered later
     * are never added, so every one of them resolves to the missing model — the
     * purple cube in the inventory — even though the block models load fine from
     * the pack. Register the newcomers here; the next client resource reload (the
     * hot-add already triggers one) calls {@code rebuildCache()} and resolves them.
     */
    private static void registerItemModels(ModContainer container) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            ItemModelShaper shaper = minecraft.getItemRenderer().getItemModelShaper();
            int added = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR || shaper.getItemModel(item) != null) {
                    continue;
                }
                shaper.register(item,
                        ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item)));
                added++;
            }
            ModReloader.LOGGER.info(
                    "[ModReloader] item model shaper: registered {} new item(s); "
                            + "the next resource reload resolves them", added);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] item model shaper registration failed", t);
        }
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
