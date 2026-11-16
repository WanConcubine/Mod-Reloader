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
    def test_data_only_is_data(self):
        report = classify(make(class_count=0, has_data=True))
        self.assertEqual(report.tier, "DATA")

    def test_native_is_huanghou(self):
        report = classify(make(native_methods=2))
        self.assertEqual(report.tier, "HUANGHOU")

    def test_registration_is_guifei(self):
        report = classify(make(registration_refs={"net/minecraft/core/Registry#register"},
                               content_refs={"net/minecraft/world/item/Item"}))
        self.assertEqual(report.tier, "GUIFEI")

    def test_mixin_without_registration_is_fei(self):
        report = classify(make(mixin_class_count=2,
                               mixin_targets={"net/minecraft/client/gui/Gui"}))
        self.assertEqual(report.tier, "FEI")

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


class AccessTransformerTest(unittest.TestCase):
    def test_access_transformer_is_reported_as_a_boundary(self):
        report = classify(make(at_rules=3,
                               at_targets={"net.minecraft.client.Minecraft",
                                           "net.minecraft.world.level.block.Block"}))
        self.assertTrue(any("access-transformer" in reason for reason in report.reasons),
                        report.reasons)
        self.assertEqual(3, report.signals["at_rules"])
        self.assertIn("net.minecraft.client.Minecraft", report.signals["at_targets"])

    def test_no_access_transformer_is_the_normal_case(self):
        report = classify(make())
        self.assertEqual(0, report.signals["at_rules"])
        self.assertFalse(any("access-transformer" in reason for reason in report.reasons))


class DeferredRegisterNestedClassesTest(unittest.TestCase):
    def test_registration_refs_from_nested_deferred_register_are_guifei(self):
        # Regression for the t4fixture miscalibration: DeferredRegister$Blocks#register
        # is the shape real NeoForge mods use, and it must land in GUIFEI.
        report = classify(make(
            registration_refs={
                "net/neoforged/neoforge/registries/DeferredRegister$Blocks#register",
            },
            content_refs={"net/minecraft/world/level/block/Block"}))
        self.assertEqual("GUIFEI", report.tier)
        self.assertIn("registration calls", " ".join(report.reasons))
