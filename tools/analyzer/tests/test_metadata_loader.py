import tempfile
import unittest
import zipfile
from pathlib import Path

from modreloader import metadata


class HybridLoaderDetectionTest(unittest.TestCase):
    """Multi-loader jars keep a fabric.mod.json sentinel (wrongloader) next to
    the real neoforge.mods.toml. Preferring Fabric loses the real metadata."""

    def test_neoforge_metadata_wins_over_fabric_sentinel(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "journeymap-like.jar"
            with zipfile.ZipFile(jar, "w") as zf:
                zf.writestr("fabric.mod.json", '{"schemaVersion":1,"id":"demo-wrongloader",'
                                              '"version":"not a fabric mod"}')
                zf.writestr("META-INF/neoforge.mods.toml",
                            'modLoader="javafml"\nloaderVersion="[4,)"\n'
                            '[[mods]]\nmodId="demo"\nversion="1.0.0"\n'
                            '[[dependencies.demo]]\nmodId="commonnetworking"\n'
                            'type="required"\nversionRange="[1.0.16,)"\n')
            info = metadata.inspect_jar(jar)
        self.assertEqual("neoforge", info.loader)
        self.assertEqual("demo", info.mod_id)
        self.assertEqual("commonnetworking", info.dependencies[0].mod_id)


if __name__ == "__main__":
    unittest.main()
