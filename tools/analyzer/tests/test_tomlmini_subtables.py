import unittest

from modreloader import tomlmini


class SubtableAfterArrayOfTablesTest(unittest.TestCase):
    """A sub-table under an array-of-tables parent must not replace the list.

    XaeroPlus ships `[[mods]]` plus `[mods."lithium:options"]`; replacing the
    list loses the mod id and makes the jar look like it has no metadata.
    """

    def test_subtable_attaches_to_last_entry(self):
        doc = tomlmini.parse(
            '[[mods]]\nmodId="first"\n'
            '[[mods]]\nmodId="second"\n'
            '[mods."lithium:options"]\n"mixin.chunk.palette"=false\n'
        )
        self.assertIsInstance(doc["mods"], list)
        self.assertEqual(2, len(doc["mods"]))
        self.assertEqual("first", doc["mods"][0]["modId"])
        self.assertEqual("second", doc["mods"][1]["modId"])
        self.assertFalse(doc["mods"][1]['"lithium:options"']["mixin.chunk.palette"])


if __name__ == "__main__":
    unittest.main()
