import unittest
from pathlib import Path

from modreloader.model import Analysis
from modreloader.rules import classify


def make(**kwargs):
    base = dict(
        path=Path("demo.jar"),
        loader="fabric",
        mod_id="demo",
        name="Demo",
        version="1.0.0",
        class_count=10,
    )
    base.update(kwargs)
    return Analysis(**base)


class RulesTest(unittest.TestCase):
    def test_data_only_is_t1(self):
        report = classify(make(class_count=0, has_data=True))
        self.assertEqual(report.tier, "T1")

    def test_native_is_t5(self):
        report = classify(make(native_methods=2))
        self.assertEqual(report.tier, "T5")

    def test_registration_is_t4(self):
        report = classify(make(registration_refs={"net/minecraft/core/Registry#register"},
                               content_refs={"net/minecraft/world/item/Item"}))
        self.assertEqual(report.tier, "T4")

    def test_mixin_without_registration_is_t3(self):
        report = classify(make(mixin_class_count=2,
                               mixin_targets={"net/minecraft/client/gui/Gui"}))
        self.assertEqual(report.tier, "T3")

    def test_intermediary_targets_lower_confidence(self):
        named = classify(make(mixin_class_count=2,
                              mixin_targets={"net/minecraft/client/gui/Gui"}))
        intermediary = classify(make(mixin_class_count=2,
                                     mixin_targets={"net/minecraft/class_310"}))
        self.assertEqual(intermediary.tier, named.tier)
        self.assertLess(intermediary.confidence, named.confidence)

    def test_unreadable_jar_is_unknown(self):
        report = classify(make(class_count=0, errors=["cannot open jar: File is not a zip file"]))
        self.assertEqual(report.tier, "T?")


if __name__ == "__main__":
    unittest.main()
