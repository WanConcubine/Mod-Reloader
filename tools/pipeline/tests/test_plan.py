import unittest

from modreloader_pipeline.corpus import Corpus, Entry, Scenario
from modreloader_pipeline.facts import DependencyFact, Facts
from modreloader_pipeline.plan import plan_scenario


def entry(name, deps=()):
    return Entry(id=name, name=name, project=name, version="1", file=name + ".jar")


def fact(name, deps=()):
    f = Facts(entry=entry(name), path=None)
    f.dependencies = [DependencyFact(mod_id=d, type="required", side="BOTH",
                                     version_range="", ordering="AFTER")
                      for d in deps]
    return f


def corpus(*entries):
    by_id = {e.id: e for e in entries}
    scenario = Scenario(id="s", title="s", jars=list(by_id))
    return Corpus(path=None, mc="1.21.1", loader="neoforge",
                  entries=by_id, scenarios={"s": scenario})


class DependencyOrderTest(unittest.TestCase):
    def test_dependency_first_is_ok(self):
        lib, app = entry("lib"), entry("app")
        c = corpus(lib, app)
        scenario = Scenario(id="s", title="s", jars=["lib", "app"], expect={"default": "pass"})
        plan = plan_scenario(c, scenario, {"lib": fact("lib"), "app": fact("app", ["lib"])}, {})
        self.assertTrue(plan.ok, plan.problems)
        self.assertEqual(["lib", "app"], [f.entry.id for f in plan.add])

    def test_dependent_before_dependency_is_blocked(self):
        lib, app = entry("lib"), entry("app")
        c = corpus(lib, app)
        scenario = Scenario(id="s", title="s", jars=["app", "lib"], expect={"default": "pass"})
        plan = plan_scenario(c, scenario, {"lib": fact("lib"), "app": fact("app", ["lib"])}, {})
        self.assertFalse(plan.ok)
        self.assertTrue(any("needs lib first" in p for p in plan.problems))

    def test_missing_dependency_is_expected_in_negative_scenario(self):
        app = entry("app")
        c = corpus(app)
        scenario = Scenario(id="s", title="s", jars=["app"],
                            expect={"default": "pass",
                                    "jars": {"app": {"hotadd": "refuse",
                                                     "kind": "MISSING_DEPENDENCY"}}})
        plan = plan_scenario(c, scenario, {"app": fact("app", ["lib"])}, {})
        self.assertTrue(plan.ok, plan.problems)
        self.assertEqual(["lib"], plan.missing_deps["app"])

    def test_missing_dependency_blocks_positive_scenario(self):
        app = entry("app")
        c = corpus(app)
        scenario = Scenario(id="s", title="s", jars=["app"], expect={"default": "pass"})
        plan = plan_scenario(c, scenario, {"app": fact("app", ["lib"])}, {})
        self.assertFalse(plan.ok)
        self.assertTrue(any("needs lib" in p for p in plan.problems))

    def test_requires_absent_conflicts_with_preloaded(self):
        app = entry("app")
        c = corpus(app)
        scenario = Scenario(id="s", title="s", jars=["app"],
                            requires_absent=["app"], expect={"default": "pass"})
        plan = plan_scenario(c, scenario, {"app": fact("app")}, {"app": "run/mods/app.jar"})
        self.assertFalse(plan.ok)
        self.assertTrue(any("requires app to be absent" in p for p in plan.problems))

    def test_preloaded_jar_is_not_hot_added(self):
        lib = entry("lib")
        c = corpus(lib)
        scenario = Scenario(id="s", title="s", jars=["lib"], expect={"default": "pass"})
        plan = plan_scenario(c, scenario, {"lib": fact("lib")}, {"lib": "run/mods/lib.jar"})
        self.assertTrue(plan.ok, plan.problems)
        self.assertEqual([], plan.add)
        self.assertEqual(["lib"], [f.entry.id for f in plan.preloaded])


if __name__ == "__main__":
    unittest.main()
