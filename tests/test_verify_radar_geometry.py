"""PC 雷达签到几何与数字码提取纯函数测试（本轮补测，双端单侧裸奔项）。

- latlon_to_xy / xy_to_latlon / circle_intersections / solve_two_points：
  原为 send_radar 体内嵌套函数（零测试引用），已提升为模块级（函数体逐字
  未动）；Android 侧同构实现已有 RollcallEngineAnswerTest 守护，本文件补上
  PC 侧，双端行为分叉可被对齐发现。
- find_number_code：递归提取 + 深度上限矩阵（Android 侧 findNumberCodeTest
  已有，PC 为独立第二实现）。
"""

from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.verify import (  # noqa: E402
    circle_intersections,
    find_number_code,
    latlon_to_xy,
    solve_two_points,
    xy_to_latlon,
)

RADIUS = 6371000


class RadarGeometryTests(unittest.TestCase):
    def test_latlon_xy_roundtrip(self):
        for lat, lon, lat0, lon0 in (
            (24.48, 118.09, 24.0, 118.0),
            (-33.86, 151.2, 0.0, 0.0),
            (0.001, -0.001, 0.0, 0.0),
        ):
            x, y = latlon_to_xy(lat, lon, lat0, lon0)
            back_lat, back_lon = xy_to_latlon(x, y, lat0, lon0)
            self.assertAlmostEqual(back_lat, lat, places=6)
            self.assertAlmostEqual(back_lon, lon, places=6)

    def test_circle_intersections_two_points(self):
        # 圆心 (0,0) r=5 与 (8,0) r=5：交点 (4, ±3)
        pair = circle_intersections(0, 0, 5, 8, 0, 5)
        self.assertIsNotNone(pair)
        (x1, y1), (x2, y2) = pair
        self.assertAlmostEqual(x1, 4.0, places=6)
        self.assertAlmostEqual(x2, 4.0, places=6)
        self.assertAlmostEqual(abs(y1), 3.0, places=6)
        self.assertAlmostEqual(abs(y2), 3.0, places=6)
        self.assertAlmostEqual(y1 + y2, 0.0, places=6)

    def test_circle_intersections_disjoint_and_contained(self):
        # 分离：圆距 20 > 5+5
        self.assertIsNone(circle_intersections(0, 0, 5, 20, 0, 5))
        # 内含：圆距 1 < |5-4|（不足 1 才内含；取 0.5）
        self.assertIsNone(circle_intersections(0, 0, 5, 0.5, 0, 4))
        # 同心（distance=0 显式早退，无除零；半径不等也走内含语义）
        self.assertIsNone(circle_intersections(0, 0, 5, 0, 0, 3))
        self.assertIsNone(circle_intersections(0, 0, 5, 0, 0, 5))

    def test_circle_intersections_tangent_clamps_to_single_point(self):
        # 外切：distance == r1 + r2，d1²-along² 浮点可为极小负数——钳 0 退化单点
        pair = circle_intersections(0, 0, 5, 10, 0, 5)
        self.assertIsNotNone(pair)
        (x1, y1), (x2, y2) = pair
        self.assertAlmostEqual(x1, x2, places=6)
        self.assertAlmostEqual(y1, y2, places=6)

    def test_solve_two_points_symmetric_equal_distances(self):
        # 等距双锚：两解关于锚点连线的中垂线对称（lat 同、lon 关于中点对称）
        lat1, lon1 = 24.0000, 118.0000
        lat2, lon2 = 24.0010, 118.0000
        span_m = math.radians(lat2 - lat1) * RADIUS  # ≈111.19m
        solutions = solve_two_points(lat1, lon1, lat2, lon2, span_m, span_m)
        self.assertIsNotNone(solutions)
        (a_lat, a_lon), (b_lat, b_lon) = solutions
        mid_lat = (lat1 + lat2) / 2
        self.assertAlmostEqual(a_lat, mid_lat, places=6)
        self.assertAlmostEqual(b_lat, mid_lat, places=6)
        self.assertAlmostEqual(a_lon + b_lon, 2 * lon1, places=6)
        self.assertNotAlmostEqual(a_lon, b_lon, places=4)

    def test_solve_two_points_no_intersection(self):
        solutions = solve_two_points(24.0, 118.0, 24.001, 118.0, 10.0, 10.0)
        self.assertIsNone(solutions)


class FindNumberCodeTests(unittest.TestCase):
    def test_top_level_and_nested_extraction(self):
        self.assertEqual(find_number_code({"number_code": "486"}), "486")
        self.assertEqual(find_number_code({"data": {"number_code": 622}}), "622")
        self.assertEqual(
            find_number_code({"rows": [{"number_code": "A17"}, {"number_code": "B18"}]}),
            "A17",
        )
        self.assertEqual(
            find_number_code([{"a": 1}, {"b": {"number_code": "N9"}}]),
            "N9",
        )

    def test_numeric_values_stringified(self):
        self.assertEqual(find_number_code({"number_code": 1234}), "1234")

    def test_missing_and_falsy(self):
        self.assertIsNone(find_number_code({}))
        self.assertIsNone(find_number_code({"number_code": None}))
        self.assertIsNone(find_number_code({"other": "text"}))
        self.assertIsNone(find_number_code([]))
        self.assertIsNone(find_number_code("plain"))

    def test_depth_limit_stops_deep_nesting(self):
        payload = {}
        node = payload
        for _ in range(20):
            node["child"] = {}
            node = node["child"]
        node["number_code"] = "deep"
        self.assertIsNone(find_number_code(payload))


if __name__ == "__main__":
    unittest.main()
