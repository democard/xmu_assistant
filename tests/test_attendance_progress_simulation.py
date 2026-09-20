"""独立离线原型的验收；不会连接学校，也不会加载应用签到提交路径。"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))
from simulate_attendance_progress import make_roster
from xmu_rollcall.rollcall_progress import classify_attendance_entry, summarize_rollcall_progress


def summarize(payload, *, roster_complete=True, my_user_no=""):
    return summarize_rollcall_progress(
        payload, roster_complete=roster_complete, my_user_no=my_user_no,
    )


class AttendanceProgressSimulationTest(unittest.TestCase):
    def test_both_public_schemas_describe_same_counts(self):
        xmu = summarize(make_roster(6, 40), roster_complete=True)
        other = summarize(make_roster(6, 40, field="rollcall_status", signed_value="on_call_fine"), roster_complete=True)
        self.assertEqual(xmu, other)
        self.assertEqual((xmu.present, xmu.absent, xmu.observed, xmu.rate_percent), (6, 34, 40, 15.0))

    def test_counts_below_at_above_reference_percentage(self):
        for present, expected in [(5, 12.5), (6, 15.0), (7, 17.5)]:
            with self.subTest(present=present):
                self.assertEqual(summarize(make_roster(present, 40), roster_complete=True).rate_percent, expected)

    def test_small_classes_do_not_round_counts_before_division(self):
        self.assertEqual(summarize(make_roster(0, 6), roster_complete=True).rate_percent, 0.0)
        self.assertAlmostEqual(summarize(make_roster(1, 7), roster_complete=True).rate_percent, 100 / 7)

    def test_missing_malformed_and_empty_are_not_zero_percent(self):
        for payload in (None, [], {}, {"student_rollcalls": None}, {"student_rollcalls": {}}, make_roster(0, 0)):
            with self.subTest(payload=payload):
                self.assertIsNone(summarize(payload, roster_complete=True).rate_percent)

    def test_partial_roster_never_claims_class_percentage(self):
        result = summarize(make_roster(2, 2), roster_complete=False)
        self.assertEqual(result.present, 2)
        self.assertFalse(result.roster_complete)
        self.assertIsNone(result.rate_percent)

    def test_nonempty_unknown_status_counts_in_denominator_without_becoming_present(self):
        for raw in ("unknown", "not_signed", "fine", "on_call_pending", "future_leave"):
            with self.subTest(raw=raw):
                result = summarize({"student_rollcalls": [{"status": raw}]}, roster_complete=True)
                self.assertEqual((result.present, result.absent, result.unknown), (0, 0, 1))
                self.assertTrue(result.reliable)
                self.assertEqual(result.rate_percent, 0.0)

    def test_missing_or_non_string_status_is_structurally_unreliable(self):
        for raw in (None, "", 1, True):
            with self.subTest(raw=raw):
                result = summarize({"student_rollcalls": [{"status": raw}]}, roster_complete=True)
                self.assertEqual((result.observed, result.unknown, result.invalid_rows), (1, 1, 1))
                self.assertFalse(result.reliable)
                self.assertIsNone(result.rate_percent)

    def test_conflicting_status_fields_are_unknown(self):
        self.assertIsNone(classify_attendance_entry({"status": "absent", "rollcall_status": "on_call_fine"}))
        self.assertTrue(classify_attendance_entry({"status": "on_call", "rollcall_status": "on_call_fine"}))

    def test_confirmed_leave_fields_are_equivalent_and_count_in_denominator(self):
        self.assertIsNone(classify_attendance_entry({
            "status": "on_leave",
            "rollcall_status": "on_personal_leave",
        }))
        payload = {"student_rollcalls": [
            *({"status": "on_call", "rollcall_status": "on_call_fine"} for _ in range(72)),
            {"status": "on_leave", "rollcall_status": "on_personal_leave"},
        ]}
        result = summarize(payload)
        self.assertEqual(
            (result.present, result.absent, result.leave, result.unknown, result.observed),
            (72, 0, 1, 0, 73),
        )
        self.assertAlmostEqual(result.rate_percent, 72 * 100 / 73)

    def test_confirmed_late_is_not_class_progress_present_but_confirms_self(self):
        result = summarize(
            {"student_rollcalls": [{
                "user_no": "me",
                "status": "on_call",
                "rollcall_status": "on_call_arrive_late",
            }]},
            my_user_no="me",
        )
        self.assertEqual((result.present, result.late, result.leave, result.unknown), (0, 1, 0, 0))
        self.assertTrue(result.own_present)
        self.assertEqual(result.rate_percent, 0.0)

    def test_supported_leave_variants_share_leave_semantics(self):
        for raw in ("on_leave", "on_personal_leave", "on_sick_leave", "on_public_leave"):
            with self.subTest(raw=raw):
                result = summarize({"student_rollcalls": [{"status": raw}]})
                self.assertEqual((result.leave, result.unknown, result.rate_percent), (1, 0, 0.0))

    def test_progress_uses_detailed_status_priority_while_personal_logic_stays_strict(self):
        result = summarize({"student_rollcalls": [
            {"status": "on_leave", "rollcall_status": "on_call_fine"},
        ]})
        self.assertEqual((result.present, result.leave, result.unknown), (1, 0, 0))
        self.assertFalse(result.own_status_ambiguous)
        self.assertEqual(result.rate_percent, 100.0)

    def test_leave_only_roster_is_reliable_zero_percent(self):
        result = summarize({"student_rollcalls": [
            {"status": "on_leave", "rollcall_status": "on_personal_leave"},
        ]})
        self.assertEqual((result.present, result.leave, result.observed), (0, 1, 1))
        self.assertTrue(result.reliable)
        self.assertEqual(result.rate_percent, 0.0)

    def test_own_leave_is_neither_present_nor_explicitly_absent(self):
        result = summarize(
            {"student_rollcalls": [{
                "user_no": "me",
                "status": "on_leave",
                "rollcall_status": "on_personal_leave",
            }]},
            my_user_no="me",
        )
        self.assertIsNone(result.own_present)
        self.assertTrue(result.own_leave)
        self.assertEqual((result.leave, result.unknown), (1, 0))

    def test_self_marker_resolves_phone_login_to_canonical_roster_row(self):
        result = summarize(
            {"student_rollcalls": [{
                "user_no": "canonical-student-number",
                "is_current_user": True,
                "status": "on_call",
            }]},
            my_user_no="phone-login",
        )
        self.assertTrue(result.own_present)

    def test_conflicting_multiple_self_markers_never_confirm_present_or_leave(self):
        result = summarize(
            {"student_rollcalls": [
                {"user_no": "one", "is_self": True, "status": "on_call"},
                {"user_no": "two", "is_current_user": True, "status": "on_leave"},
            ]},
            my_user_no="phone-login",
        )
        self.assertIsNone(result.own_present)
        self.assertFalse(result.own_leave)
        self.assertTrue(result.own_status_ambiguous)

    def test_matched_conflicting_status_fields_are_personally_ambiguous(self):
        result = summarize(
            {"student_rollcalls": [{
                "user_no": "me",
                "status": "on_call",
                "rollcall_status": "absent",
            }]},
            my_user_no="me",
        )
        self.assertIsNone(result.own_present)
        self.assertFalse(result.own_leave)
        self.assertTrue(result.own_status_ambiguous)

        unmatched = summarize(
            {"student_rollcalls": [{"user_no": "other", "status": "on_call"}]},
            my_user_no="me",
        )
        self.assertFalse(unmatched.own_status_ambiguous)

    def test_empty_primary_field_can_use_explicit_secondary_field(self):
        self.assertTrue(classify_attendance_entry({"status": None, "student_rollcall_status": "on_call_fine"}))

    def test_malformed_rows_invalidate_denominator(self):
        payload = make_roster(1, 2)
        payload["student_rollcalls"].append(None)
        result = summarize(payload, roster_complete=True)
        self.assertEqual((result.observed, result.invalid_rows), (2, 1))
        self.assertIsNone(result.rate_percent)

    def test_reference_minimal_status_roster_needs_no_identity_or_extra_flag(self):
        result = summarize(
            {"student_rollcalls": [{"status": "on_call"}, {"status": "absent"}]},
        )
        self.assertEqual((result.present, result.absent, result.observed), (1, 1, 2))
        self.assertTrue(result.roster_complete)
        self.assertEqual(result.rate_percent, 50.0)

    def test_shared_reference_fixtures_match_android_results(self):
        import json
        path = ROOT / "android/app/src/test/resources/attendance_progress_reference_cases.json"
        cases = json.loads(path.read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(case=case["name"]):
                result = summarize_rollcall_progress(case["payload"])
                expected = case["expected"]
                self.assertEqual((result.present, result.absent, result.observed),
                                 (expected["present"], expected["absent"], expected["total"]))
                self.assertEqual(result.leave, expected.get("leave", 0))
                self.assertEqual(result.unknown, expected.get("unknown", 0))
                if expected["percent"] is None:
                    self.assertIsNone(result.rate_percent)
                    self.assertFalse(result.reliable)
                else:
                    self.assertAlmostEqual(result.rate_percent, expected["percent"])
                    self.assertTrue(result.reliable)

    def test_duplicate_students_are_not_double_counted(self):
        payload = make_roster(1, 1)
        payload["student_rollcalls"] *= 2
        result = summarize(payload, roster_complete=True)
        self.assertEqual((result.present, result.observed, result.duplicate_rows), (1, 1, 1))
        self.assertIsNone(result.rate_percent)

    def test_duplicate_conflict_invalidates_personal_confirmation(self):
        payload = {"student_rollcalls": [
            {"user_no": "me", "status": "on_call"}, {"user_no": "me", "status": "absent"},
        ]}
        result = summarize(payload, roster_complete=True, my_user_no="me")
        self.assertEqual((result.present, result.unknown), (0, 1))
        self.assertIsNone(result.own_present)

    def test_whole_class_signed_does_not_confirm_unmatched_person(self):
        payload = make_roster(2, 2)
        payload["status"] = "on_call_fine"
        result = summarize(payload, roster_complete=True, my_user_no="missing")
        self.assertEqual(result.rate_percent, 100.0)
        self.assertIsNone(result.own_present)

    def test_own_status_requires_matching_student(self):
        payload = make_roster(1, 2)
        self.assertTrue(summarize(payload, my_user_no="simulation-0").own_present)
        self.assertFalse(summarize(payload, my_user_no="simulation-1").own_present)

    def test_snapshot_is_not_reused_across_events_or_accounts(self):
        self.assertTrue(summarize(make_roster(1, 1), my_user_no="simulation-0").own_present)
        later = summarize({}, my_user_no="other")
        self.assertIsNone(later.own_present)
        self.assertEqual(later.present, 0)

    def test_does_not_mutate_input(self):
        import copy
        payload = make_roster(2, 3)
        before = copy.deepcopy(payload)
        summarize(payload, roster_complete=True)
        self.assertEqual(payload, before)

    def test_count_invariant_for_small_classes(self):
        for total in range(1, 25):
            for present in range(total + 1):
                result = summarize(make_roster(present, total), roster_complete=True)
                self.assertEqual(
                    result.present + result.absent + result.leave + result.late + result.unknown,
                    result.observed,
                )
                self.assertAlmostEqual(result.rate_percent, present * 100 / total)


class ExistingDetailReaderSimulationTest(unittest.TestCase):
    """复用本项目现有 GET 读取器接模拟返回，而非重写网络代码。"""

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
        from xmu_rollcall.desktop_qt import core
        cls.core = core

    def test_existing_get_can_feed_both_schemas_without_extra_request(self):
        from unittest.mock import Mock, patch
        for field, signed in [("status", "on_call"), ("rollcall_status", "on_call_fine")]:
            response = Mock(status_code=200, headers={"Content-Type": "application/json"},
                            url="https://lnt.xmu.edu.cn/api/rollcall/simulation/student_rollcalls", history=[])
            response.json.return_value = make_roster(6, 40, field=field, signed_value=signed)
            session = Mock(spec=["get"])
            session.get.return_value = response
            with patch("requests.sessions.Session.request", side_effect=AssertionError("禁止真实网络")):
                detail = self.core.fetch_student_rollcall_detail(session, "simulation")
                result = summarize_rollcall_progress(detail)
                self.assertEqual(result.rate_percent, 15.0)
            # mock_calls 也包含 response.json()，请求次数只统计 get。
            self.assertEqual(session.get.call_count, 1)
            self.assertEqual(session.get.call_args.args[0], response.url)

    def test_existing_read_failure_does_not_become_zero_percent(self):
        from unittest.mock import Mock
        session = Mock(spec=["get"])
        session.get.side_effect = TimeoutError("模拟超时")
        result = summarize(self.core.fetch_student_rollcall_detail(session, "simulation"))
        self.assertIsNone(result.rate_percent)


if __name__ == "__main__":
    unittest.main()
