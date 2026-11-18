import tempfile
import unittest
from pathlib import Path

from modreloader_pipeline.corpus import Corpus, CorpusError
from modreloader_pipeline.facts import inspect_entry

CORPUS = Path(__file__).resolve().parents[1] / "corpus.json"


class CorpusFileTest(unittest.TestCase):
    def test_shipped_corpus_is_valid(self):
        corpus = Corpus.load(CORPUS)
        self.assertEqual("1.21.1", corpus.mc)
        self.assertEqual("neoforge", corpus.loader)
        self.assertGreaterEqual(len(corpus.entries), 10)
        for scenario in corpus.scenarios.values():
            for jar in scenario.jars:
                self.assertIn(jar, corpus.entries)
            for jar, spec in (scenario.expect.get("jars") or {}).items():
                self.assertIn(jar, scenario.jars)
                self.assertIn(spec.get("hotadd", "pass"), ("pass", "refuse", "unknown"))

    def test_duplicate_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "corpus.json"
            path.write_text(
                '{"schema":1,"mc":"1.21.1","loader":"neoforge",'
                '"entries":[{"id":"a","name":"a","project":"a","version":"1","file":"a.jar"},'
                '{"id":"a","name":"a","project":"a","version":"1","file":"a.jar"}],'
                '"scenarios":[{"id":"s","title":"s","jars":["a"]}]}',
                encoding="utf-8")
            with self.assertRaises(CorpusError):
                Corpus.load(path)

    def test_missing_jar_reports_where_it_looked(self):
        corpus = Corpus.load(CORPUS)
        entry = corpus.entries["mousetweaks"]
        with tempfile.TemporaryDirectory() as tmp:
            facts = inspect_entry(entry, Path(tmp))
        self.assertFalse(facts.present)
        self.assertTrue(any("jar not found" in e for e in facts.errors))


if __name__ == "__main__":
    unittest.main()
