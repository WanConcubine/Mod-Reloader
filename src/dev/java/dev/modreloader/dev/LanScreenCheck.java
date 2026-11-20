package dev.modreloader.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.modreloader.ModReloader;
import dev.modreloader.reflect.JvmAccess;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only end-to-end check for A1.2. Enabled with {@code -PverifyLan=true}.
 *
 * <p>Creates a fresh world through the real {@link CreateWorldScreen}, waits for
 * the client to join it, opens {@link ShareToLanScreen} exactly like the pause
 * menu does, then checks the mixin-added members ({@code lsp_objects},
 * {@code getPort}, the port edit box) and writes a screenshot before exiting.
 *
 * <p>This is not shipped behavior: it only runs when the system property
 * {@code modreloader.verifyLan} is true.
 */
public final class LanScreenCheck {
    private enum Stage {
        WORLD, OPEN_LAN, INSPECT, SCREENSHOT, DONE
    }

    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int WORLD_SETTLE_TICKS = 10;

    private static final TestWorld WORLD = new TestWorld(WORLD_SETTLE_TICKS);
    private static Stage stage = Stage.WORLD;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean failed;

    private LanScreenCheck() {
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[LanCheck] started; creating a test world");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, LanScreenCheck::tick);
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout after " + TIMEOUT_TICKS / 20 + "s at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WORLD -> openWorld(mc);
            case OPEN_LAN -> openLanScreen(mc);
            case INSPECT -> inspectLanScreen(mc);
            case SCREENSHOT -> screenshotAndFinish(mc);
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void openWorld(Minecraft mc) {
        TestWorld.Result result = WORLD.tick(mc);
        if (result.error() != null) {
            fail(result.error());
            return;
        }
        if (!result.ready()) {
            return;
        }
        ModReloader.LOGGER.info("[LanCheck] world loaded: {} ({})", mc.level.dimension().location(),
                mc.level.getClass().getSimpleName());
        stage = Stage.OPEN_LAN;
        ticks = 0;
    }

    private static void openLanScreen(Minecraft mc) {
        boolean loadedBefore = isClassLoaded("net.minecraft.client.gui.screens.ShareToLanScreen");
        ModReloader.LOGGER.info("[LanCheck] ShareToLanScreen loaded before opening={}", loadedBefore);
        try {
            Class<?> direct = Class.forName("net.minecraft.client.gui.screens.ShareToLanScreen", false,
                    LanScreenCheck.class.getClassLoader());
            Class<?> viaMc = Class.forName("net.minecraft.client.gui.screens.ShareToLanScreen", false,
                    Minecraft.class.getClassLoader());
            ModReloader.LOGGER.info("[LanCheck] class identity direct==viaMc {}; loaders {} / {}; "
                            + "lsp_objects direct={} viaMc={}",
                    direct == viaMc, direct.getClassLoader(), viaMc.getClassLoader(),
                    hasDeclaredField(direct, "lsp_objects"), hasDeclaredField(viaMc, "lsp_objects"));
            ShareToLanScreen screen = new ShareToLanScreen(null);
            ModReloader.LOGGER.info("[LanCheck] new screen class={}; lsp_objects={}; interfaces={}",
                    screen.getClass(), hasDeclaredField(screen.getClass(), "lsp_objects"),
                    java.util.Arrays.toString(screen.getClass().getInterfaces()));
            mc.setScreen(screen);
            stage = Stage.INSPECT;
            ticks = 0;
        } catch (Throwable t) {
            Screen current = mc.screen;
            if (current != null) {
                ModReloader.LOGGER.error("[LanCheck] after failure screen={}; lsp_objects={}; interfaces={}",
                        current.getClass(), hasDeclaredField(current.getClass(), "lsp_objects"),
                        java.util.Arrays.toString(current.getClass().getInterfaces()));
            }
            fail("opening ShareToLanScreen failed: " + t);
        }
    }

    private static void inspectLanScreen(Minecraft mc) {
        if (!(mc.screen instanceof ShareToLanScreen screen)) {
            if (ticks > 40) {
                fail("ShareToLanScreen is not the current screen: "
                        + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
            }
            return;
        }
        if (ticks == 5) {
            inspect(screen);
        }
        if (ticks >= 10) {
            stage = Stage.SCREENSHOT;
            ticks = 0;
        }
    }

    private static void inspect(ShareToLanScreen screen) {
        ModReloader.LOGGER.info("[LanCheck] screen={} title='{}'", screen.getClass().getName(),
                screen.getTitle().getString());

        boolean hasField = hasDeclaredField(screen.getClass(), "lsp_objects");
        Object lspObjects = null;
        if (hasField) {
            try {
                lspObjects = UnsafeAccess.get(screen, screen.getClass(), "lsp_objects");
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[LanCheck] cannot read lsp_objects", t);
            }
        }
        ModReloader.LOGGER.info("[LanCheck] ShareToLanScreen.lsp_objects present={} value={}",
                hasField, lspObjects == null ? "null" : lspObjects.getClass().getName());
        if (!hasField || lspObjects == null) {
            fail("mixin field lsp_objects missing/null");
        }

        Integer port = null;
        try {
            Method getPort = screen.getClass().getMethod("getPort");
            port = (Integer) getPort.invoke(screen);
            ModReloader.LOGGER.info("[LanCheck] ShareToLanScreen.getPort()={}", port);
        } catch (Throwable t) {
            fail("ShareToLanScreen.getPort() missing/failed: " + t);
        }
        if (hasDeclaredMethod(screen.getClass(), "movePortEditBox", int.class, int.class, int.class, int.class)) {
            ModReloader.LOGGER.info("[LanCheck] ShareToLanScreen.movePortEditBox(IIII) present");
        } else {
            fail("ShareToLanScreen.movePortEditBox(IIII) missing");
        }

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = screen.children();
        int edits = 0;
        for (var child : children) {
            if (child instanceof EditBox box) {
                edits++;
                ModReloader.LOGGER.info("[LanCheck] EditBox value='{}' bounds=({},{},{},{}) class={}",
                        box.getValue(), box.getX(), box.getY(), box.getWidth(), box.getHeight(),
                        box.getClass().getSimpleName());
            } else if (child instanceof AbstractButton button) {
                ModReloader.LOGGER.info("[LanCheck] Button '{}' bounds=({},{},{},{}) active={}",
                        button.getMessage().getString(), button.getX(), button.getY(),
                        button.getWidth(), button.getHeight(), button.active);
            }
        }
        if (edits == 0) {
            fail("no port EditBox found on ShareToLanScreen");
        } else {
            ModReloader.LOGGER.info("[LanCheck] port edit boxes found: {}", edits);
        }

        if (hasDeclaredMethod("net.minecraft.server.players.PlayerList", "setMaxPlayers", int.class)) {
            ModReloader.LOGGER.info("[LanCheck] PlayerList.setMaxPlayers(int) present (mixin applied on load)");
        } else {
            ModReloader.LOGGER.warn("[LanCheck] PlayerList.setMaxPlayers(int) not visible "
                    + "(PlayerList may not be loaded or mixin did not apply)");
        }

        if (!failed) {
            ModReloader.LOGGER.info("[LanCheck] A1.2 LAN screen checks passed (port={})", port);
        }
    }

    private static void screenshotAndFinish(Minecraft mc) {
        if (ticks < 10) {
            return;
        }
        Path file = mc.gameDirectory.toPath().resolve("screenshots").resolve("a1-lan-screen.png");
        try {
            Files.createDirectories(file.getParent());
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                image.writeToFile(file);
            } finally {
                image.close();
            }
            ModReloader.LOGGER.info("[LanCheck] screenshot: {}", file.toAbsolutePath());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[LanCheck] screenshot failed", t);
        }
        if (!failed) {
            ModReloader.LOGGER.info("[LanCheck] RESULT: PASS");
        } else {
            ModReloader.LOGGER.error("[LanCheck] RESULT: FAIL");
        }
        mc.setScreen(null);
        stage = Stage.DONE;
        ticks = 0;
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            ModReloader.LOGGER.info("[LanCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[LanCheck] FAILED: {}", reason);
        // Try to leave evidence and shut down instead of hanging the run.
        Minecraft mc = Minecraft.getInstance();
        try {
            Path file = mc.gameDirectory.toPath().resolve("screenshots").resolve("a1-lan-failure.png");
            Files.createDirectories(file.getParent());
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                image.writeToFile(file);
            } finally {
                image.close();
            }
            ModReloader.LOGGER.error("[LanCheck] failure screenshot: {}", file.toAbsolutePath());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[LanCheck] failure screenshot failed", t);
        }
        ModReloader.LOGGER.error("[LanCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }


    private static boolean hasDeclaredField(Class<?> type, String name) {
        try {
            type.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static boolean hasDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasDeclaredMethod(String className, String name, Class<?>... parameterTypes) {
        try {
            Class<?> type = Class.forName(className, false,
                    Thread.currentThread().getContextClassLoader());
            return hasDeclaredMethod(type, name, parameterTypes);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isClassLoaded(String name) {
        try {
            for (Class<?> type : JvmAccess.get().getAllLoadedClasses()) {
                if (name.equals(type.getName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[LanCheck] cannot inspect loaded classes", t);
        }
        return false;
    }

    private static String describe(Screen screen) {
        StringBuilder out = new StringBuilder();
        for (var child : screen.children()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(child.getClass().getSimpleName());
        }
        return out.toString();
    }
}
