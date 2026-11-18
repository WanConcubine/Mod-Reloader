import unittest
from pathlib import Path

from modreloader_pipeline.runner import (
    RunOutcome,
    build_command,
    overall_verdict,
    parse_compat_log,
    parse_focus,
)
from modreloader_pipeline.facts import Facts
from modreloader_pipeline.corpus import Entry

LOG = """\
[CompatCheck] queue: [/tmp/a.jar, /tmp/b.jar]
[CompatCheck] START MouseTweaks-neoforge-mc1.21-2.26.1.jar predicted=FEI blocks=1060 items=1333
[CompatCheck] RESULT MouseTweaks-neoforge-mc1.21-2.26.1.jar predicted=FEI ok=true stage=NONE kind=NONE isLoaded=true blocks 1060->1060 items 1333->1333 msg=hot-added [mousetweaks]
[CompatCheck] RESULT Jade-1.21.1-NeoForge-15.10.6.jar predicted=GUIFEI ok=false stage=VALIDATE kind=ACCESS_TRANSFORMER isLoaded=false blocks 1060->1060 items 1333->1333 msg=Jade ships META-INF/accesstransformer.cfg
[CompatCheck] DONE 2/2
"""


class ParseCompatLogTest(unittest.TestCase):
    def test_result_fields(self):
        results, starts, done = parse_compat_log(LOG)
        self.assertEqual(["MouseTweaks-neoforge-mc1.21-2.26.1.jar"], starts)
        self.assertEqual((2, 2), done)
        mouse = results["MouseTweaks-neoforge-mc1.21-2.26.1.jar"]
        self.assertTrue(mouse.ok)
        self.assertTrue(mouse.is_loaded)
        self.assertEqual("FEI", mouse.tier)
        jade = results["Jade-1.21.1-NeoForge-15.10.6.jar"]
        self.assertFalse(jade.ok)
        self.assertEqual("ACCESS_TRANSFORMER", jade.kind)
        self.assertEqual((1060, 1060), (jade.blocks_before, jade.blocks_after))

    def test_build_command_order_and_nojbr(self):
        facts = []
        for name in ("a.jar", "b.jar"):
            entry = Entry(id=name, name=name, project="p", version="1", file=name)
            facts.append(Facts(entry=entry, path=Path("/tmp") / name))
        command = build_command(None, facts, jbr=False, jbr_java="/jbr/bin/java")
        self.assertIn("-PverifyCompat=/tmp/a.jar,/tmp/b.jar", command)
        self.assertIn("-PnoJbr=true", command)
        self.assertFalse(any(part.startswith("-PjbrJava=") for part in command))
        command = build_command(None, facts, jbr=True, jbr_java="/jbr/bin/java")
        self.assertIn("-PjbrJava=/jbr/bin/java", command)
        self.assertNotIn("-PnoJbr=true", command)


class FocusGuardTest(unittest.TestCase):
    def test_focus_line_is_parsed(self):
        self.assertFalse(parse_focus("[CompatCheck] window focused=false (noFocus=true)"))
        self.assertTrue(parse_focus("[CompatCheck] window focused=true (noFocus=true)"))
        self.assertIsNone(parse_focus("no focus line"))

    def test_focus_stolen_is_not_a_pass(self):
        outcome = RunOutcome(command=[], exit_code=0, timed_out=False, duration_s=1.0,
                             log_path=Path("/tmp/x"), done=(1, 1), window_focused=True)
        self.assertEqual("FOCUS_STOLEN", overall_verdict([], outcome, no_focus=True))
        self.assertNotEqual("FOCUS_STOLEN", overall_verdict([], outcome, no_focus=False))


class PathOrderTest(unittest.TestCase):
    def test_no_jbr_flag_is_last(self):
        entry = Entry(id="a", name="a", project="p", version="1", file="a.jar")
        command = build_command(None, [Facts(entry=entry, path=Path("/tmp/a.jar"))], jbr=False)
        self.assertEqual("-PnoJbr=true", command[-1])


if __name__ == "__main__":
    unittest.main()
