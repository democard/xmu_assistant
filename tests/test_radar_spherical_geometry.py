"""Offline spherical radar geometry tests; no server metric assumption."""
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall.radar_geometry import EARTH_RADIUS_METERS, solve_spherical_candidates


def haversine(a, b):
    p1, l1 = map(math.radians, a)
    p2, l2 = map(math.radians, b)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin((l2 - l1) / 2) ** 2
    return 2 * EARTH_RADIUS_METERS * math.asin(math.sqrt(max(0, min(1, h))))


class SphericalRadarGeometryTests(unittest.TestCase):
    def assert_target(self, a, b, target, tolerance=0.001):
        d1, d2 = haversine(a, target), haversine(b, target)
        candidates = solve_spherical_candidates(*a, *b, d1, d2)
        self.assertIsNotNone(candidates)
        self.assertEqual(len(candidates), 2)
        self.assertLess(min(haversine(p, target) for p in candidates), tolerance)
        for point in candidates:
            self.assertAlmostEqual(haversine(a, point), d1, delta=tolerance)
            self.assertAlmostEqual(haversine(b, point), d2, delta=tolerance)
        return candidates

    def test_fixed_probes_recover_exact_targets(self):
        for target in ((24.4371, 118.0975), (24.45, 118.1)):
            with self.subTest(target=target):
                self.assert_target((24.3, 118.0), (24.6, 118.2), target)

    def test_antimeridian_poles_and_large_circles(self):
        for a, b, target in (
            ((10, 179), (10, -179), (10.5, 180)),
            ((89, 0), (89, 90), (90, 0)),
            ((0, 0), (0, 30), (0, 160)),
            ((0, 0), (0, 0.001), (0.0002, 0.0004)),
        ):
            with self.subTest(target=target):
                self.assert_target(a, b, target)

    def test_external_internal_and_zero_radius_tangency(self):
        for target in ((0, 0.4), (0, -0.2), (0, 0), (0, 180)):
            with self.subTest(target=target):
                candidates = self.assert_target((0, 0), (0, 1), target)
                self.assertEqual(candidates[0], candidates[1])

    def test_nonintersection_and_impossible_large_circles(self):
        # Centers 111 km apart; disjoint and contained small circles.
        for d1, d2 in ((1000, 1000), (200000, 1000), (math.pi * EARTH_RADIUS_METERS - 1000,) * 2):
            self.assertIsNone(solve_spherical_candidates(0, 0, 0, 1, d1, d2))

    def test_coincident_and_antipodal_centers(self):
        for b in ((0, 0), (0, 360), (0, 180)):
            self.assertIsNone(solve_spherical_candidates(0, 0, *b, 1000, 1000))

    def test_invalid_values(self):
        valid = [24.3, 118.0, 24.6, 118.2, 50000.0, 50000.0]
        for index in range(6):
            for value in (float("nan"), float("inf"), -float("inf"), None, "12", True, 10 ** 1000):
                args = valid.copy()
                args[index] = value
                with self.subTest(index=index, value=value):
                    self.assertIsNone(solve_spherical_candidates(*args))
        for index, value in ((0, 91), (2, -91), (4, -1), (5, math.pi * EARTH_RADIUS_METERS + 1)):
            args = valid.copy()
            args[index] = value
            self.assertIsNone(solve_spherical_candidates(*args))


if __name__ == "__main__":
    unittest.main()
