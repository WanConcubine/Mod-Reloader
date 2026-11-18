import tempfile
import unittest
import zipfile
from pathlib import Path

from modreloader import metadata

TOML = """\
modLoader="javafml"
loaderVersion="[4,)"
license="MIT"

[[mods]]
modId="demo"
version="1.0.0"
displayName="Demo"

[[dependencies.demo]]
modId="minecraft"
type="required"
versionRange="[1.21.1,1.22)"
side="BOTH"
ordering="NONE"

[[dependencies.demo]]
modId="searchables"
type="required"
versionRange="[1.0,2.0)"
side="BOTH"
ordering="AFTER"

[[dependencies.demo]]
modId="jei"
type="optional"
versionRange="[19,)"
side="CLIENT"
ordering="NONE"
"""


class DependencyMetadataTest(unittest.TestCase):
    """A "前置" is only mandatory when type=required. The pipeline uses this to
    decide whether a missing jar blocks a scenario or is merely a feature gap."""

    def _jar(self, directory: Path) -> Path:
        jar = directory / "demo.jar"
        with zipfile.ZipFile(jar, "w") as zf:
            zf.writestr("META-INF/neoforge.mods.toml", TOML)
        return jar

    def test_required_and_optional_are_distinguished(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = metadata.inspect_jar(self._jar(Path(tmp)))
        by_id = {dep.mod_id: dep for dep in info.dependencies}
        self.assertEqual("required", by_id["searchables"].type)
        self.assertTrue(by_id["searchables"].required)
        self.assertEqual("[1.0,2.0)", by_id["searchables"].version_range)
        self.assertEqual("AFTER", by_id["searchables"].ordering)
        self.assertEqual("optional", by_id["jei"].type)
        self.assertFalse(by_id["jei"].required)
        self.assertEqual("CLIENT", by_id["jei"].side)

    def test_platform_ids_are_not_reported_as_installable_deps(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = metadata.inspect_jar(self._jar(Path(tmp)))
        self.assertEqual({"searchables": "[1.0,2.0)"}, info.depends)


if __name__ == "__main__":
    unittest.main()
