package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Guifei-tier stage: applies a hot-added mod's {@code data/} (recipes, loot
 * tables, tags) to the running server without a restart.
 *
 * <p>Why this is separate from {@link RegistryStage}: the two halves of a
 * content mod live in different places. Blocks/items are <em>registry content</em>
 * (frozen at startup, replayed by {@code RegistryStage}); recipes/loot/tags are
 * <em>datapack content</em>, loaded by {@code WorldLoader} from the server's
 * {@code PackRepository} at world load. The mod's jar was not in that repository,
 * so its data was never read — that is the dogfood gap this stage closes.
 *
 * <p>Shape of the operation, deliberately the same as {@code /reload}:
 *
 * <ol>
 *   <li>build a {@link Pack} for the mod jar and add it to the server repository
 *       as a {@code RepositorySource}, so a later {@code /reload} keeps it;</li>
 *   <li>open the pack ({@code PackRepository#reload}) and select it explicitly;</li>
 *   <li>call {@code MinecraftServer#reloadResources(selectedIds)} — the vanilla
 *       reload entry point. It rebuilds {@code ReloadableServerResources}
 *       (recipe manager, tag manager, loot/datapack registries, functions,
 *       advancements), re-binds tags and syncs recipes/tags to connected players.</li>
 * </ol>
 *
 * <p><b>Ordering is not optional.</b> The reload resolves recipe ingredients and
 * loot table item ids against the registries: it must run <em>after</em>
 * {@code RegistryStage.commit}, otherwise every Ex Nihilo item id in the data
 * would be unknown.
 *
 * <p>Limits, all verified or documented rather than assumed:
 *
 * <ul>
 *   <li>add-only: no datapack removal, no update-in-place (the same limits as the
 *       registry tier);</li>
 *   <li>datapack <em>registries</em> shipped by the mod (dimension/biome/…) are
 *       loaded into the new registry holder but are not applied to the running
 *       world — that is world-load semantics, not {@code /reload};</li>
 *   <li>custom reload listeners registered through {@code @EventBusSubscriber}
 *       (e.g. Ex Nihilo's hammer/sieve/barrel tables) only run if FML registered
 *       the subscriber at startup — hot-added subscriber classes are not scanned;
 *       the log says so instead of pretending the data loaded;</li>
 *   <li>a title-screen add has no server yet: the pack is deferred and injected
 *       on the next {@code ServerStartedEvent}, which costs one extra reload at
 *       world start.</li>
 * </ul>
 */
public final class DatapackStage {
    private static final long TIMEOUT_SECONDS = 300;
    private static final List<Pending> PENDING = new CopyOnWriteArrayList<>();
    private static boolean deferredListenerInstalled;

    /** {@code applied=false} means "nothing to do / no server yet", never "failed". */
    public record Result(boolean applied, String detail) {
    }

    private record Pending(IModFileInfo fileInfo, Set<String> modIds) {
    }

    private DatapackStage() {
    }

    /**
     * Applies the mod's data pack to {@code server}, or defers it when there is
     * no server yet (client at the title screen).
     *
     * @throws IllegalStateException when the pack cannot be built or the reload
     *         fails; at this point the registry commit already happened, so the
     *         caller must treat the runtime as dirty
     */
    public static Result apply(MinecraftServer server, IModFileInfo fileInfo, Set<String> modIds) {
        if (!hasData(fileInfo)) {
            return new Result(false, "no data/ directory in the jar");
        }
        if (server == null) {
            defer(fileInfo, modIds);
            return new Result(false,
                    "no server yet (title screen); the data pack is applied when a world starts");
        }
        if (server.isSameThread()) {
            return inject(server, fileInfo, modIds);
        }
        // The GUI and the dev harness hot-add on the client thread, but the pack
        // repository and ReloadableServerResources belong to the server thread.
        // Hop over and wait: mutating the repository off-thread would race with
        // the server's own reads.
        CompletableFuture<Result> hop = new CompletableFuture<>();
        server.execute(() -> {
            try {
                hop.complete(inject(server, fileInfo, modIds));
            } catch (Throwable t) {
                hop.completeExceptionally(t);
            }
        });
        return await(hop);
    }

    private static Result inject(MinecraftServer server, IModFileInfo fileInfo, Set<String> modIds) {
        String packId = packId(modIds);
        PackRepository repository = server.getPackRepository();

        Counts before = counts(server, modIds);
        if (before.anyContent()) {
            // A title-screen add happens before the world exists. NeoForge then
            // builds its mod pack cache while the world loads, so the mod is
            // already in the repository and its data is live. A reload here would
            // only duplicate the pack; say so instead of doing it.
            String detail = "already live (" + before.describe() + "); no reload needed";
            ModReloader.LOGGER.info("[ModReloader] datapack for {}: {}", modIds, detail);
            return new Result(true, detail);
        }

        if (!repository.isAvailable(packId)) {
            Pack pack = createPack(fileInfo, modIds, packId);
            repository.addPackFinder(acceptor -> acceptor.accept(pack));
            // reload() rebuilds `available` from the repository sources; adding
            // the finder keeps this pack discoverable for every later /reload.
            repository.reload();
            if (!repository.isAvailable(packId)) {
                throw new IllegalStateException("pack " + packId
                        + " was not discovered by the server pack repository");
            }
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>(repository.getSelectedIds());
        if (!selected.add(packId)) {
            return new Result(true, "pack " + packId + " was already selected");
        }

        CompletableFuture<Void> reload = server.reloadResources(selected);
        await(reload);
        Counts after = counts(server, modIds);

        String detail = String.format(Locale.ROOT,
                "pack %s selected; recipes %d -> %d, loot tables %d -> %d, "
                        + "block tags %d -> %d, item tags %d -> %d%s",
                packId,
                before.recipes(), after.recipes(),
                before.lootTables(), after.lootTables(),
                before.blockTags(), after.blockTags(),
                before.itemTags(), after.itemTags(),
                after.recipes() + after.lootTables() == 0 ? " (the jar carries no recipe/loot data)" : "");
        ModReloader.LOGGER.info("[ModReloader] datapack reload: {}", detail);
        ModReloader.LOGGER.info("[ModReloader] server data packs after reload: {}",
                repository.getSelectedPacks().stream().map(Pack::getId).toList());
        if (after.recipeErrors() && !before.recipeErrors()) {
            ModReloader.LOGGER.warn("[ModReloader] recipe manager reported load errors; "
                    + "recipes whose ingredients reference missing items/tags were skipped");
        }
        return new Result(true, detail);
    }

    private static Pack createPack(IModFileInfo fileInfo, Set<String> modIds, String packId) {
        String version = fileInfo.getMods().stream()
                .map(IModInfo::getVersion)
                .map(Object::toString)
                .collect(Collectors.joining(","));
        PackLocationInfo location = new PackLocationInfo(
                packId,
                Component.literal(fileInfo.getFile().getFileName() + " (hot-added data)"),
                PackSource.DEFAULT,
                Optional.of(new KnownPack(ModReloader.MOD_ID, packId, version)));
        Pack.ResourcesSupplier resources = ResourcePackLoader.createPackForMod(fileInfo);
        // readWithOptionalMeta tolerates a mod pack.mcmeta without (or with a
        // stale) pack_format; /reload does not enforce compatibility anyway.
        Pack pack;
        try {
            pack = ResourcePackLoader.readWithOptionalMeta(location, resources,
                    PackType.SERVER_DATA, new PackSelectionConfig(false, Pack.Position.TOP, false));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the data pack metadata of "
                    + fileInfo.getFile().getFileName(), e);
        }
        ModReloader.LOGGER.info("[ModReloader] datapack pack {} built for {} (compatibility {})",
                packId, modIds, pack.getCompatibility());
        return pack;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "the server did not finish the data reload within " + TIMEOUT_SECONDS + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the data reload", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("the data reload failed: " + cause, cause);
        }
    }

    private static void defer(IModFileInfo fileInfo, Set<String> modIds) {
        String packId = packId(modIds);
        Set<String> ids = Collections.unmodifiableSet(new LinkedHashSet<>(modIds));
        PENDING.removeIf(pending -> packId(pending.modIds()).equals(packId));
        PENDING.add(new Pending(fileInfo, ids));
        if (!deferredListenerInstalled) {
            deferredListenerInstalled = true;
            NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, DatapackStage::onServerStarted);
        }
        ModReloader.LOGGER.info(
                "[ModReloader] datapack for {} deferred: no server yet; it will be injected "
                        + "when a world starts (one extra data reload)", modIds);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> pending = List.copyOf(PENDING);
        PENDING.clear();
        for (Pending entry : pending) {
            try {
                ModReloader.LOGGER.info("[ModReloader] deferred datapack: {}",
                        inject(event.getServer(), entry.fileInfo(), entry.modIds()).detail());
            } catch (Throwable t) {
                // Never fail world start for this: the mod is already registered;
                // its data simply is not loaded, which the user fixes by restarting
                // with the jar in mods/.
                ModReloader.LOGGER.error("[ModReloader] deferred datapack injection failed for {}; "
                        + "its recipes/loot/tags are not loaded (restart with the jar in mods/)",
                        entry.modIds(), t);
            }
        }
    }

    /** True when the jar actually ships {@code data/} (nothing to inject otherwise). */
    public static boolean hasData(IModFileInfo fileInfo) {
        try {
            Path root = fileInfo.getFile().getSecureJar().getRootPath();
            return Files.isDirectory(root.resolve("data"));
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot inspect data/ of {}",
                    fileInfo.getFile().getFileName(), t);
            return false;
        }
    }

    /**
     * Matches NeoForge's own mod pack id ({@code mod/<modId>[,<modId>]}), so a
     * later restart with the jar in {@code mods/} resolves the same id instead of
     * reporting it as a missing data pack.
     */
    private static String packId(Set<String> modIds) {
        return "mod/" + String.join(",", modIds);
    }

    private record Counts(long recipes, long lootTables, long blockTags, long itemTags,
                          boolean recipeErrors) {
        boolean anyContent() {
            return recipes + lootTables + blockTags + itemTags > 0;
        }

        String describe() {
            return "recipes=" + recipes + ", loot tables=" + lootTables
                    + ", block tags=" + blockTags + ", item tags=" + itemTags;
        }
    }

    private static Counts counts(MinecraftServer server, Set<String> modIds) {
        long recipes = server.getRecipeManager().getRecipeIds()
                .filter(id -> modIds.contains(id.getNamespace()))
                .count();
        long lootTables = server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).stream()
                .filter(id -> modIds.contains(id.getNamespace()))
                .count();
        RegistryAccess.Frozen access = server.reloadableRegistries().get();
        return new Counts(recipes, lootTables,
                countTags(access.registryOrThrow(Registries.BLOCK), modIds),
                countTags(access.registryOrThrow(Registries.ITEM), modIds),
                server.getRecipeManager().hadErrorsLoading());
    }

    private static long countTags(Registry<?> registry, Set<String> modIds) {
        return registry.getTagNames()
                .filter(tag -> modIds.contains(tag.location().getNamespace()))
                .count();
    }
}
