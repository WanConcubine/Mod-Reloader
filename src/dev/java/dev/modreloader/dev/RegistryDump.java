package dev.modreloader.dev;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dev-only: a registry dump both sides of the multiplayer registry-sync check can
 * compare.
 *
 * <p>Ids are what matter, so the format is one {@code kind id key} line per entry
 * (blocks, items) plus {@code state id block[properties]} for block states: the
 * state ids are the ones chunk sections are serialised with, so a mapping that
 * agrees on items but not on states would corrupt terrain instead of items.
 */
final class RegistryDump {
    private RegistryDump() {
    }

    /** Sorted, stable lines for one side. */
    static List<String> lines() {
        List<String> lines = new ArrayList<>();
        BuiltInRegistries.BLOCK.forEach(block -> lines.add(
                "block " + BuiltInRegistries.BLOCK.getId(block) + " "
                        + BuiltInRegistries.BLOCK.getKey(block)));
        BuiltInRegistries.ITEM.forEach(item -> lines.add(
                "item " + BuiltInRegistries.ITEM.getId(item) + " "
                        + BuiltInRegistries.ITEM.getKey(item)));
        BuiltInRegistries.BLOCK.forEach(block -> {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                lines.add("state " + Block.getId(state) + " "
                        + BuiltInRegistries.BLOCK.getKey(block) + state.toString());
            }
        });
        lines.sort(String::compareTo);
        return lines;
    }

    /**
     * @return one entry per difference, capped so a total mismatch stays readable
     */
    static List<String> differences(List<String> expected, List<String> actual) {
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(actual);
        List<String> extra = new ArrayList<>(actual);
        extra.removeAll(expected);
        List<String> result = new ArrayList<>();
        for (String line : missing) {
            if (result.size() >= 8) {
                break;
            }
            result.add("missing: " + line);
        }
        for (String line : extra) {
            if (result.size() >= 16) {
                break;
            }
            result.add("extra: " + line);
        }
        if (missing.size() + extra.size() > result.size()) {
            result.add("... " + (missing.size() + extra.size()) + " difference(s) total");
        }
        return result;
    }

    /** Convenience for the entry that makes a mismatch easy to spot in a log. */
    static String describe(Map<ResourceLocation, Integer> ids) {
        StringBuilder builder = new StringBuilder();
        ids.forEach((key, id) -> {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(key).append('=').append(id);
        });
        return builder.toString();
    }
}
