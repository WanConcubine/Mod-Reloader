package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;

/**
 * The single exit for a startup-only event replayed against one container.
 *
 * <p>Callers pass the catalog name of the event next to the event object, so the
 * thing the registration audit claims to cover
 * ({@link dev.modreloader.inspect.ReplayCatalog}) and the thing actually posted
 * cannot be two different lists. A mismatch - an event that was swapped out but
 * not renamed in the catalog - is logged as a warning at runtime.
 *
 * <p>Failure handling stays at the call site on purpose: lifecycle events
 * ({@code FMLClientSetupEvent}, payload registration, registry commit) must fail
 * the add, while cosmetic replays only warn. Swallowing both here would blur
 * that line.
 */
public final class OneShotReplay {
    private OneShotReplay() {
    }

    public static <T extends Event & IModBusEvent> void fire(
            ModContainer container, String expectedEvent, T event) {
        String actual = event.getClass().getName();
        if (!actual.equals(expectedEvent)) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] replay event mismatch for {}: catalog says {}, posted {}; "
                            + "the registration audit will grade the wrong event",
                    container.getModId(), expectedEvent, actual);
        }
        container.acceptEvent(event);
    }
}