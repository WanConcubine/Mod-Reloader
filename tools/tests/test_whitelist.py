import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import whitelist  # noqa: E402


HEADER = "\t".join(whitelist.COLUMNS)


def row(name, jvm="stock", bucket="T1", tier="PIN", state="candidate", bug="-", evidence="harness"):
    return "\t".join([name, name + ".jar", "0123456789abcdef", jvm, bucket, tier, bug, state, evidence, "2026-11-17"])


class WhitelistTest(unittest.TestCase):
    def write(self, *rows):
        handle = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False)
        handle.write("\n".join([HEADER, *rows]) + "\n")
        handle.close()
        return Path(handle.name)

    def test_render_groups_by_environment_and_bucket(self):
        path = self.write(
            row("Zed", bucket="BLACK", state="verified", bug="AT"),
            row("Alpha", bucket="T1"),
            row("JbrOnly", jvm="jbr", bucket="J1"),
        )
        rows, errors = whitelist.load(path)
        self.assertEqual([], whitelist.check(rows, errors))
        text = whitelist.render(rows)
        self.assertLess(text.index("Alpha"), text.index("Zed"), "T1 must come before BLACK")
        self.assertIn("stock", text)
        self.assertIn("JBR", text)
        self.assertIn("JbrOnly", text)
        self.assertIn("T1 × 1", text)
        self.assertIn("BLACK × 1", text)

    def test_check_rejects_bucket_for_wrong_environment(self):
        rows, errors = whitelist.load(self.write(row("Bad", jvm="jbr", bucket="T1")))
        errors = whitelist.check(rows, errors)
        self.assertTrue(any("not valid for jvm" in e for e in errors), errors)

    def test_check_rejects_duplicate_mod_and_jvm(self):
        rows, errors = whitelist.load(self.write(row("Same"), row("Same")))
        errors = whitelist.check(rows, errors)
        self.assertTrue(any("duplicate row" in e for e in errors), errors)

    def test_check_rejects_unknown_state(self):
        rows, errors = whitelist.load(self.write(row("Bad", state="maybe")))
        errors = whitelist.check(rows, errors)
        self.assertTrue(any("state 'maybe'" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main()
