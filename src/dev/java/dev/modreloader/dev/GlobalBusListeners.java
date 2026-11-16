package dev.modreloader.dev;

import dev.modreloader.hotadd.UnsafeAccess;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Map;

/**
 * Read-only count of the listeners on {@code NeoForge.EVENT_BUS}.
 *
 * <p>Why this exists: a hot-add that fails <em>after</em> construct leaves the
 * mod's game-bus listeners registered — NeoForge's event bus has no unregister and
 * the rollback only restores ModList/module/mixin/pack. The count is the evidence:
 * before/after a forced post-construct failure it grows, and a retry would grow it
 * again (the listener runs twice per event). Dev-only.
 */
public final class GlobalBusListeners {
    private GlobalBusListeners() {
    }

    /** Total listeners across all event classes, or -1 when the bus cannot be read. */
    public static int count() {
        try {
            Object listeners = UnsafeAccess.get(NeoForge.EVENT_BUS,
                    net.neoforged.bus.EventBus.class, "listeners");
            if (!(listeners instanceof Map<?, ?> map)) {
                return -1;
            }
            int total = 0;
            for (Object value : map.values()) {
                if (value instanceof List<?> list) {
                    total += list.size();
                }
            }
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }
}
