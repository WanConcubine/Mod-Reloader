package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dev-only L2 check for hot-added Create (issues 65 and 66).
 *
 * <p>Hot-adds Create at the title screen, creates a world, and then exercises the
 * two paths that used to fail:
 *
 * <ul>
 *   <li><b>Issue 65</b>: {@code PonderRenderTypes.<clinit>} calls ponder's static
 *       {@code @Invoker} interface. The interface is a loadable mixin that Mixin
 *       rewrites only when the interface class is defined, so a config prepared
 *       without Mixin's coprocessor listeners left the
 *       {@code AssertionError("Mixin application failed!")} stub.</li>
 *   <li><b>Issue 66</b>: Create registers {@code create:potato_projectile/type}
 *       through {@code DataPackRegistryEvent.NewRegistry}. When the event is not
 *       replayed the level registry access does not know it and rendering the
 *       potato cannon in the creative inventory throws
 *       {@code IllegalStateException: Registry ... not found}.</li>
 * </ul>
 *
 * <p>{@code -PverifyCreatePonder=<create jar>}
 */
public final class CreatePonderCheck {
    private static final String PONDER_RENDER_TYPES = "net.createmod.catnip.render.PonderRenderTypes";
    private static final String POTATO_ITEM = "com.simibubi.create.content.equipment.potatoCannon.PotatoCannonItem";
    private static final String POTATO_REGISTRY = "create:potato_projectile/type";
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int AFTER_ADD_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 420;

    private static Path jar;
    private static int ticks;
    private static int totalTicks;
    private static boolean added;
    private static boolean worldStarted;
    private static boolean worldReady;
    private static boolean done;
    private static TestWorld world;

    private CreatePonderCheck() {
    }

    public static void start(Path target) {
        if (jar != null || done) {
            return;
        }
        jar = target.toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[CreatePonderCheck] armed: jar={}", jar.getFileName());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (done) {
            return;
        }
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            finish(false, List.of("timeout"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!added && (totalTicks < TITLE_SETTLE_TICKS || mc.getOverlay() != null)) {
            return;
        }
        if (!added) {
            added = true;
            HotAddEngine.Result result;
            try {
                result = HotAddEngine.add(jar);
            } catch (Throwable t) {
                finish(false, List.of("hot-add threw " + t.getClass().getSimpleName() + ": "
                        + t.getMessage()));
                return;
            }
            ModReloader.LOGGER.info("[CreatePonderCheck] hot-add result: ok={} stage={} kind={} msg={}",
                    result.ok(), result.stage(), result.kind(), result.message());
            if (!result.ok()) {
                finish(false, List.of("hot-add failed at " + result.stage() + "/" + result.kind()
                        + ": " + result.message()));
                return;
            }
            ticks = 0;
            return;
        }
        if (++ticks < AFTER_ADD_TICKS) {
            return;
        }
        if (!worldStarted) {
            worldStarted = true;
            world = new TestWorld(20);
        }
        if (!worldReady) {
            TestWorld.Result progress = world.tick(mc);
            if (progress.error() != null) {
                finish(false, List.of("test world: " + progress.error()));
                return;
            }
            if (!progress.ready()) {
                return;
            }
            worldReady = true;
        }
        check(mc);
    }

    private static void check(Minecraft mc) {
        List<String> problems = new ArrayList<>();
        checkRegistry(mc, problems);
        checkPotatoCannon(mc, problems);
        checkPonderRenderTypes(problems);
        finish(problems.isEmpty(), problems);
    }

    private static void checkRegistry(Minecraft mc, List<String> problems) {
        if (mc.level == null) {
            problems.add("no level after the test world reported ready");
            return;
        }
        ResourceKey<Registry<Object>> key =
                ResourceKey.createRegistryKey(ResourceLocation.parse(POTATO_REGISTRY));
        Optional<Registry<Object>> registry = mc.level.registryAccess().registry(key);
        if (registry.isEmpty()) {
            problems.add("datapack registry " + POTATO_REGISTRY
                    + " is missing from the level registry access");
            return;
        }
        int size = registry.get().size();
        ModReloader.LOGGER.info("[CreatePonderCheck] {} entries={}", POTATO_REGISTRY, size);
        if (size == 0) {
            problems.add("datapack registry " + POTATO_REGISTRY
                    + " exists but has no entries (its data pack was not loaded)");
        }
    }

    /** The item decorator that crashed rendering the creative tab. */
    private static void checkPotatoCannon(Minecraft mc, List<String> problems) {
        if (mc.player == null) {
            problems.add("no player after the test world reported ready");
            return;
        }
        Optional<?> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:potato_cannon"));
        if (item.isEmpty()) {
            problems.add("create:potato_cannon is not registered");
            return;
        }
        try {
            Class<?> cannon = Class.forName(POTATO_ITEM, true,
                    CreatePonderCheck.class.getClassLoader());
            Method getAmmo = cannon.getMethod("getAmmo", Player.class, ItemStack.class);
            Object ammo = getAmmo.invoke(null, mc.player, new ItemStack((net.minecraft.world.item.Item) item.get()));
            ModReloader.LOGGER.info("[CreatePonderCheck] PotatoCannonItem.getAmmo() = {}", ammo);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            problems.add("PotatoCannonItem.getAmmo() threw " + cause.getClass().getSimpleName() + ": "
                    + cause.getMessage() + " (issue 66: datapack registry missing)");
        }
    }

    private static void checkPonderRenderTypes(List<String> problems) {
        Class<?> ponder;
        try {
            ponder = Class.forName(PONDER_RENDER_TYPES, true,
                    CreatePonderCheck.class.getClassLoader());
        } catch (Throwable t) {
            Throwable cause = t instanceof ExceptionInInitializerError && t.getCause() != null
                    ? t.getCause() : t;
            problems.add(PONDER_RENDER_TYPES + " failed to initialise: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return;
        }
        for (String method : List.of("fluid", "outlineSolid")) {
            try {
                Object value = ponder.getMethod(method).invoke(null);
                ModReloader.LOGGER.info("[CreatePonderCheck] {}.{}() = {}", PONDER_RENDER_TYPES,
                        method, value);
                if (value == null) {
                    problems.add(PONDER_RENDER_TYPES + "." + method + "() returned null");
                }
            } catch (Throwable t) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                problems.add(PONDER_RENDER_TYPES + "." + method + "() threw "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }

    private static void finish(boolean ok, List<String> problems) {
        done = true;
        if (ok) {
            ModReloader.LOGGER.info("[CreatePonderCheck] RESULT: PASS - after a hot-add, a new world "
                    + "has Create's datapack registries and the potato cannon decorator and Ponder "
                    + "render types both work");
        } else {
            for (String problem : problems) {
                ModReloader.LOGGER.error("[CreatePonderCheck] problem: {}", problem);
            }
            ModReloader.LOGGER.error("[CreatePonderCheck] RESULT: FAIL - {}", problems);
        }
        Minecraft.getInstance().stop();
    }
}