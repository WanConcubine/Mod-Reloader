import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from modreloader.mixins import read_configs


def config_in_jar(payload: dict, classes=(), name: str = "demo.mixins.json") -> Path:
    """Real jars are too big to commit; a config + stub class entries suffice."""
    handle = tempfile.NamedTemporaryFile(suffix=".jar", delete=False)
    handle.close()
    with zipfile.ZipFile(handle.name, "w") as zf:
        zf.writestr(name, json.dumps(payload))
        for class_name in classes:
            zf.writestr(class_name.replace(".", "/") + ".class", b"\xca\xfe\xba\xbe")
    return Path(handle.name)


class PackageRelativeEntriesTest(unittest.TestCase):
    """Mixin resolves entries against `package`; sub-package dots are normal.

    journeymap-neoforge-1.21.1-6.0.9 (26 mixins written as `client.Foo` /
    `server.Bar`) came out as "0 mixin classes" -> PIN, while the runtime
    inspector said FEI. PIN is the dangerous direction: the dialog then says
    "loadable".
    """

    def test_subpackage_entries_resolve_to_real_classes(self):
        jar = config_in_jar(
            {
                "package": "com.example.mixin",
                "client": ["client.GuiMixin", "PlainMixin"],
                "server": ["server.ChunkAccessor"],
            },
            classes=[
                "com.example.mixin.client.GuiMixin",
                "com.example.mixin.PlainMixin",
                "com.example.mixin.server.ChunkAccessor",
            ],
        )
        config = read_configs(jar, ["demo.mixins.json"])[0]
        self.assertEqual(
            ["com.example.mixin.client.GuiMixin",
             "com.example.mixin.PlainMixin",
             "com.example.mixin.server.ChunkAccessor"],
            config.mixin_classes,
        )

    def test_fully_qualified_entries_stay_untouched(self):
        jar = config_in_jar(
            {"package": "com.example.mixin", "mixins": ["com.example.mixin.AlreadyQualified"]},
            classes=["com.example.mixin.AlreadyQualified"],
        )
        config = read_configs(jar, ["demo.mixins.json"])[0]
        self.assertEqual(["com.example.mixin.AlreadyQualified"], config.mixin_classes)

    def test_foreign_entry_is_kept_when_that_is_what_exists(self):
        jar = config_in_jar(
            {"package": "com.example.mixin", "mixins": ["other.pkg.Mixin"]},
            classes=["other.pkg.Mixin"],
        )
        config = read_configs(jar, ["demo.mixins.json"])[0]
        self.assertEqual(["other.pkg.Mixin"], config.mixin_classes)

    def test_without_evidence_the_relative_form_wins(self):
        # Config-only jar (no class entries): keep the spec-conformant relative
        # name so the presence check downstream reports 0 rather than a lie.
        jar = config_in_jar({"package": "com.example.mixin", "mixins": ["client.Foo"]})
        config = read_configs(jar, ["demo.mixins.json"])[0]
        self.assertEqual(["com.example.mixin.client.Foo"], config.mixin_classes)


if __name__ == "__main__":
    unittest.main()
