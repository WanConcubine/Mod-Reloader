import unittest

from modreloader import tomlmini


SAMPLE = '''
modLoader = "javafml"
loaderVersion = "[1,)"
license = "CC-BY-NC-SA-4.0"

[[mods]]
modId = "jade"
version = "${file.jarVersion}"
displayName = "Jade"
authors = "Snownee"

[[mixins]]
config = "jade.mixins.json"

[[dependencies.jade]]
modId = "neoforge"
type = "required"
versionRange = "[21.0.143, )"
ordering = "NONE"
'''


class TomlMiniTest(unittest.TestCase):
    def test_scalars_and_sections(self):
        doc = tomlmini.parse(SAMPLE)
        self.assertEqual(doc["modLoader"], "javafml")
        # This used to swallow the whole file: "[1,)" contains '[' but is a string.
        self.assertEqual(doc["loaderVersion"], "[1,)")
        self.assertEqual(doc["mods"][0]["modId"], "jade")
        self.assertEqual(doc["mods"][0]["displayName"], "Jade")
        self.assertEqual(doc["mixins"][0]["config"], "jade.mixins.json")
        self.assertEqual(doc["dependencies"]["jade"][0]["versionRange"], "[21.0.143, )")

    def test_arrays(self):
        doc = tomlmini.parse('a = ["x", "y"]\nb = [1, 2, 3]\n')
        self.assertEqual(doc["a"], ["x", "y"])
        self.assertEqual(doc["b"], [1, 2, 3])

    def test_comments(self):
        doc = tomlmini.parse('# hi\nkey = "value" # tail\n')
        self.assertEqual(doc["key"], "value")


if __name__ == "__main__":
    unittest.main()
