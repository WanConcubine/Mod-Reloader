package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the access-transformer parsing and the "datagen-only" classification.
 *
 * <p>Pure on purpose: no game classes on the test classpath. The classification
 * decides what the refusal message tells the user (and whether a jar's AT is a
 * real runtime boundary), so it must not drift silently.
 */
class AccessTransformerRulesTest {
    private static final String SAMPLE = """
            # comment line
            public net/minecraft/world/level/block/Block blockState
            public-f net/minecraft/client/gui/components/EditBox displayPos
            public net/minecraft/world/level/block/Block
            public net/minecraft/data/recipes/RecipeProvider recipeProvider
            """;

    @Test
    void parsesClassAndMemberRules() {
        AccessTransformerRules rules = AccessTransformerRules.parse(SAMPLE);

        // Two rules on Block, one each on the other targets.
        assertEquals(4, rules.ruleCount());
        assertEquals(3, rules.targetCount());
        assertEquals(2, rules.rulesPerTarget().get("net.minecraft.world.level.block.Block"));
        assertEquals(1, rules.rulesPerTarget().get("net.minecraft.client.gui.components.EditBox"));
        assertEquals(1,
                rules.rulesPerTarget().get("net.minecraft.data.recipes.RecipeProvider"));
    }

    @Test
    void inlineCommentsDoNotBecomeMembersOrMethods() {
        AccessTransformerRules rules = AccessTransformerRules.parse("""
                public-f com/mojang/blaze3d/platform/NativeImage # NativeImage
                public net/minecraft/client/Minecraft fps # fps
                public net/minecraft/client/Minecraft addCustomNbtData(Lnet/minecraft/world/item/ItemStack;)V # add
                """);

        assertEquals(3, rules.ruleCount());
        assertTrue(rules.rules().get(0).classLevel(), rules.rules().get(0).toString());
        assertEquals("fps", rules.rules().get(1).member());
        assertEquals("addCustomNbtData", rules.rules().get(2).member());
        assertEquals("(Lnet/minecraft/world/item/ItemStack;)V",
                rules.rules().get(2).descriptor());
        assertTrue(rules.rules().get(2).method());
    }

    @Test
    void datagenTargetsAreSeparatedFromRuntimeOnes() {
        AccessTransformerRules rules = AccessTransformerRules.parse(SAMPLE);

        assertEquals(2, rules.runtimeTargets().size());
        assertEquals("net.minecraft.data.recipes.RecipeProvider",
                rules.datagenTargets().get(0));
        assertFalse(rules.datagenOnly());
        assertTrue(rules.describe().contains("2 runtime"), rules.describe());
        assertTrue(rules.describe().contains("1 datagen-only"), rules.describe());
    }

    @Test
    void datagenOnlyRulesAreRecognised() {
        AccessTransformerRules rules = AccessTransformerRules.parse(
                "public net/minecraft/data/recipes/RecipeProvider recipeProvider\n");

        assertTrue(rules.datagenOnly());
        assertTrue(rules.runtimeTargets().isEmpty());
        assertTrue(rules.describe().contains("every target is a datagen class"),
                rules.describe());
    }

    @Test
    void datagenAndGameTestPackagesCountAsNonRuntime() {
        assertTrue(AccessTransformerRules.isDatagenClass(
                "net.minecraft.data.recipes.RecipeProvider"));
        assertTrue(AccessTransformerRules.isDatagenClass(
                "net.neoforged.neoforge.data.loading.DatagenModLoader"));
        assertTrue(AccessTransformerRules.isDatagenClass("com.example.mod.datagen.Foo"));
        assertTrue(AccessTransformerRules.isDatagenClass("net.minecraft.gametest.framework.GameTestHelper"));
        assertFalse(AccessTransformerRules.isDatagenClass("net.minecraft.world.level.block.Block"));
        assertFalse(AccessTransformerRules.isDatagenClass("com.example.mod.blocks.DataBlock"));
    }

    @Test
    void emptyAndMalformedInputAreSafe() {
        AccessTransformerRules empty = AccessTransformerRules.parse("# nothing here\n\n");
        assertEquals(0, empty.ruleCount());
        assertEquals("no access transformer rules", empty.describe());
        assertFalse(empty.datagenOnly());

        // A line with only a modifier is skipped, not counted.
        AccessTransformerRules broken = AccessTransformerRules.parse("public\npublic x\n");
        assertEquals(1, broken.ruleCount());
        assertEquals(1, broken.targetCount());
    }

    @Test
    void readsTheRulesOutOfAJar(@TempDir Path dir) throws IOException {
        Path withAt = dir.resolve("with-at.jar");
        writeJar(withAt, AccessTransformerRules.ENTRY,
                "public net/minecraft/world/level/block/Block blockState\n");
        AccessTransformerRules rules = AccessTransformerRules.read(withAt);
        assertEquals(1, rules.ruleCount());
        assertEquals("net.minecraft.world.level.block.Block", rules.targets().get(0));

        Path withoutAt = dir.resolve("without-at.jar");
        writeJar(withoutAt, "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n");
        assertEquals(0, AccessTransformerRules.read(withoutAt).ruleCount());
    }

    private static void writeJar(Path jar, String entryName, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
