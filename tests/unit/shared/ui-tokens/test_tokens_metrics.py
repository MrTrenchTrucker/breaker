"""The fixed dimensions are pinned in both files.

The design states hard edges (corner radius at most 4), a touch target of at
least 48, and breakpoints at 640 and 1024. Each is asserted against tokens.css
and tokens.kt separately, so an edit to one file alone is caught here even if
the parity test were changed.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER


class MetricsTest(unittest.TestCase):
    def setUp(self):
        root = ts.read_css()["root"]
        kotlin = ts.read_kotlin()["metrics"]
        self.values = {
            "tokens.css": {
                "radius": ts.css_px(root["radius"]),
                "touch": ts.css_px(root["touch-target-min"]),
                "mobile": ts.css_px(root["breakpoint-mobile"]),
                "tablet": ts.css_px(root["breakpoint-tablet"]),
            },
            "tokens.kt": {
                "radius": kotlin["cornerRadiusDp"],
                "touch": kotlin["minTouchTargetDp"],
                "mobile": kotlin["mobileBreakpointDp"],
                "tablet": kotlin["tabletBreakpointDp"],
            },
        }

    def test_corner_radius_is_at_most_4(self):
        for name, v in self.values.items():
            with self.subTest(file=name):
                self.assertLessEqual(
                    v["radius"], 4,
                    f"ui-tokens: {name} sets a corner radius of {v['radius']}; the design's "
                    f"hard edges allow at most 4 ({CARD}, Style rules)",
                )

    def test_touch_target_is_at_least_48(self):
        for name, v in self.values.items():
            with self.subTest(file=name):
                self.assertGreaterEqual(
                    v["touch"], 48,
                    f"ui-tokens: {name} sets a minimum touch target of {v['touch']}; the "
                    f"design requires at least 48 ({CARD}, Style rules)",
                )

    def test_breakpoints_are_exactly_640_and_1024(self):
        for name, v in self.values.items():
            with self.subTest(file=name):
                self.assertEqual(
                    (v["mobile"], v["tablet"]), (640, 1024),
                    f"ui-tokens: {name} puts the breakpoints at {v['mobile']} and "
                    f"{v['tablet']}; the design fixes them at 640 and 1024 ({CARD}, Responsive)",
                )

    def test_led_segment_range_is_12_to_16(self):
        root = ts.read_css()["root"]
        led = ts.read_kotlin()["led"]
        self.assertEqual((ts.css_int(root["led-segments-min"]), ts.css_int(root["led-segments-max"])), (12, 16))
        self.assertEqual((led["minSegments"], led["maxSegments"]), (12, 16))


if __name__ == "__main__":
    unittest.main()
