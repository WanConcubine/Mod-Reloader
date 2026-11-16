import tempfile
import unittest
import zipfile
from pathlib import Path

from modreloader import metadata


class AccessTransformerParsingTest(unittest.TestCase):
    """The analyzer must see NeoForge access transformers: the runtime refuses
    such mods (FailureKind.ACCESS_TRANSFORMER), so a tier without that fact would
    look like "hot-addable" for 6 of the 9 real mods in the matrix corpus.
    """

    def _jar(self, directory: Path, at_text=None) -> Path:
        jar = directory / "mod.jar"
        with zipfile.ZipFile(jar, "w") as zf:
            zf.writestr("META-INF/neoforge.mods.toml",
                        'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n'
                        '[[mods]]\nmodId="demo"\nversion="1.0.0"\ndisplayName="Demo"\n')
            if at_text is not None:
                zf.writestr(metadata.AT_ENTRY, at_text)
        return jar

    def test_rules_and_targets_are_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = self._jar(Path(tmp),
                            "# comment\n"
                            "public net/minecraft/client/Minecraft minecraft\n"
                            "public-f net/minecraft/client/gui/components/EditBox displayPos\n"
                            "public net/minecraft/world/level/block/Block blockState\n")
            info = metadata.inspect_jar(jar)
        self.assertEqual(3, info.at_rules)
        self.assertEqual(["net.minecraft.client.Minecraft",
                          "net.minecraft.client.gui.components.EditBox",
                          "net.minecraft.world.level.block.Block"], info.at_targets)

    def test_jar_without_at_has_zero_rules(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = self._jar(Path(tmp))
            info = metadata.inspect_jar(jar)
        self.assertEqual(0, info.at_rules)
        self.assertEqual([], info.at_targets)
