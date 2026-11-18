import unittest

import calibrate


class DirectionTest(unittest.TestCase):
    """The calibration gate must not treat both error directions the same."""

    def test_exact_match_in_any_case(self):
        self.assertEqual(calibrate.direction("guifei", "GUIFEI"), "exact")
        self.assertEqual(calibrate.direction("fei", "fei"), "exact")

    def test_over_and_under(self):
        self.assertEqual(calibrate.direction("fei", "GUIFEI"), "over")
        self.assertEqual(calibrate.direction("guifei", "PIN"), "under")
        self.assertEqual(calibrate.direction("pin", "FEI"), "over")

    def test_failed_analysis_is_its_own_bucket(self):
        self.assertEqual(calibrate.direction("pin", "T?"), "unknown")
        self.assertEqual(calibrate.direction("pin", "-"), "unknown")
        self.assertEqual(calibrate.direction("-", "pin"), "unknown")

    def test_rank_ignores_placeholders(self):
        self.assertIsNone(calibrate.rank("-"))
        self.assertIsNone(calibrate.rank("T?"))
        self.assertIsNone(calibrate.rank(""))
        self.assertEqual(calibrate.rank("pin"), 2)


class ExitCodeTest(unittest.TestCase):
    def test_clean_run_passes(self):
        self.assertEqual(calibrate.exit_code(0, 0, 0, False), 0)

    def test_under_estimate_always_fails(self):
        self.assertEqual(calibrate.exit_code(1, 0, 0, True), 1)

    def test_failed_analysis_fails(self):
        self.assertEqual(calibrate.exit_code(0, 1, 0, True), 1)

    def test_over_estimate_only_fails_by_default(self):
        self.assertEqual(calibrate.exit_code(0, 0, 1, False), 1)
        self.assertEqual(calibrate.exit_code(0, 0, 1, True), 0)


if __name__ == "__main__":
    unittest.main()
