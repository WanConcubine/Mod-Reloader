package dev.modreloader.server;

import com.mojang.brigadier.CommandDispatcher;
import dev.modreloader.ModReloader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Captures the server's command dispatcher at start-up and re-fires
 * {@link RegisterCommandsEvent} after a hot-add.
 *
 * <p>Why: the event is fired once when {@code ReloadableServerResources} is
 * created, long before a mod is hot-added, so a freshly added mod's listener
 * never runs and its commands never exist. Re-firing it makes them appear.
 *
 * <p>Caveat: the event is a global bus event, so **every** already-loaded mod's
 * listener runs again. Brigadier merges duplicate nodes, so re-registration is
 * normally idempotent, but a mod with side effects in its command listener would
 * see them twice.
 */
public final class ServerCommands {
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static Commands.CommandSelection selection;
    private static CommandBuildContext context;

    private ServerCommands() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(ServerCommands::capture);
    }

    private static void capture(RegisterCommandsEvent event) {
        dispatcher = event.getDispatcher();
        selection = event.getCommandSelection();
        context = event.getBuildContext();
    }

    public static boolean available() {
        return dispatcher != null && context != null;
    }

    /** Re-fires the event so hot-added mods can register their commands. */
    public static void replay() {
        if (!available()) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] no command dispatcher captured; hot-added mods cannot add commands");
            return;
        }
        int before = dispatcher.getRoot().getChildren().size();
        NeoForge.EVENT_BUS.post(new RegisterCommandsEvent(dispatcher, selection, context));
        int after = dispatcher.getRoot().getChildren().size();
        ModReloader.LOGGER.info(
                "[ModReloader] replayed RegisterCommandsEvent; root commands {} -> {}",
                before, after);
    }
}
