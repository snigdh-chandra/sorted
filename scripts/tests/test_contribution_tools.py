"""Fictional fixtures only: exercise contribution checks and public report integrity."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import validate_contribution_data as validator
import build_benchmark_report as report

SCHEMA = json.loads(validator.SCHEMA_PATH.read_text(encoding="utf-8"))
EXAMPLES = [
    json.loads(line) for line in
    (ROOT / "data/contributions/examples/synthetic-samples.jsonl").read_text(encoding="utf-8").splitlines()
]


class ContributionValidationTest(unittest.TestCase):
    def setUp(self):
        self.row = copy.deepcopy(EXAMPLES[0])

    def errors(self):
        return validator.validate_record(self.row, SCHEMA)

    def test_all_synthetic_drafts_are_valid_but_not_training_approved(self):
        for row in EXAMPLES:
            self.assertEqual([], validator.validate_record(row, SCHEMA))
            self.assertEqual("synthetic", row["origin"])
            self.assertEqual("draft", row["review"]["status"])
            self.assertFalse(row["permissions"]["training_allowed"])

    def test_missing_field_is_rejected(self):
        del self.row["privacy"]
        self.assertTrue(self.errors())

    def test_unexpected_personal_field_is_rejected(self):
        self.row["original_inbox_id"] = "private-placeholder"
        self.assertTrue(self.errors())

    def test_boolean_is_not_a_numeric_amount(self):
        self.row["expected"]["amount"] = True
        self.assertTrue(self.errors())

    def test_non_finite_and_extremely_large_amounts_are_rejected(self):
        for number in [float("nan"), float("inf"), 10 ** 500]:
            self.row["expected"]["amount"] = number
            self.assertTrue(self.errors())

    def test_invalid_calendar_date_is_rejected(self):
        self.row["expected"]["date"] = "2026-02-30"
        self.assertTrue(self.errors())

    def test_time_without_seconds_is_valid_and_invalid_hour_is_rejected(self):
        self.assertEqual([], self.errors())
        self.row["expected"]["time"] = "25:20"
        self.assertTrue(self.errors())

    def test_evidence_must_occur_in_sanitized_text(self):
        self.row["evidence"][0]["text"] = "This quote never occurred"
        self.assertTrue(self.errors())

    def test_private_context_cannot_be_a_shared_category_target(self):
        self.row["expected"]["context_required"] = True
        self.assertTrue(self.errors())

    def test_future_payment_cannot_supply_completed_category_target(self):
        self.row["expected"].update(status="pending", bank_cash_movement="not_confirmed")
        self.assertTrue(self.errors())

    def test_unknown_amount_is_preserved_for_a_confirmed_dividend(self):
        row = next(r for r in EXAMPLES if r["id"] == "example-dividend-rate")
        self.assertIsNone(row["expected"]["amount"])
        self.assertEqual("confirmed", row["expected"]["bank_cash_movement"])
        self.assertEqual([], validator.validate_record(row, SCHEMA))

    def test_zero_amount_does_not_establish_cash_movement(self):
        self.row["expected"]["amount"] = 0
        self.assertTrue(self.errors())

    def test_refund_cannot_have_debit_direction(self):
        self.row = copy.deepcopy(next(r for r in EXAMPLES if r["id"] == "example-card-refund"))
        self.row["expected"]["direction"] = "debit"
        self.assertTrue(self.errors())

    def test_abstention_has_no_invented_category(self):
        self.row["expected"].update(category_status="abstain", category_basis="insufficient")
        self.assertTrue(self.errors())

    def test_draft_cannot_claim_human_verified_fields(self):
        self.row["review"]["fields"] = ["category"]
        self.assertTrue(self.errors())

    def test_partial_human_review_is_explicit(self):
        self.row["review"] = {"status": "human_reviewed", "method": "assistant_then_human", "fields": ["category", "category_status"]}
        self.assertEqual([], self.errors())

    def test_obvious_identifier_and_secret_red_flags(self):
        # Deliberately fictional detector probes, never copied from a source message.
        for suffix in [
            " Phone 9876543210", " owner@personal.invalid", " PAN ABCDE1234F",
            " OTP: 123456", " 123456 is your OTP", " https://bank.invalid/token",
        ]:
            self.row = copy.deepcopy(EXAMPLES[0])
            self.row["message"] += suffix
            self.assertTrue(self.errors())

    def test_reserved_example_identifiers_and_fictional_zero_code_are_allowed(self):
        self.row["message"] += " RECIPIENT_A@example https://example.test/help OTP_REDACTED"
        self.assertEqual([], self.errors())
        otp = next(r for r in EXAMPLES if r["id"] == "example-fictional-otp")
        self.assertEqual([], validator.validate_record(otp, SCHEMA))

    def test_truncated_url_reports_a_red_flag_without_crashing(self):
        self.row["message"] += " http://[broken"
        self.assertTrue(self.errors())

    def test_sharing_permission_is_required_and_training_permission_is_separate(self):
        self.row["permissions"]["public_share"] = False
        self.assertTrue(self.errors())
        self.row["permissions"] = {"public_share": True, "training_allowed": False}
        self.assertEqual([], self.errors())

    def test_self_variant_is_rejected(self):
        self.row["groups"]["variant_of"] = self.row["id"]
        self.assertTrue(self.errors())

    def run_cli(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "examples.jsonl"
            path.write_text(contents, encoding="utf-8")
            return subprocess.run([sys.executable, str(ROOT / "scripts/validate_contribution_data.py"), str(path)], capture_output=True, text=True)

    def test_cli_rejects_duplicate_bodies_without_printing_them(self):
        second = copy.deepcopy(self.row)
        second["id"] = "another-example"
        result = self.run_cli(json.dumps(self.row) + "\n" + json.dumps(second) + "\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("duplicate message body", result.stderr)
        self.assertNotIn(self.row["message"], result.stderr)

    def test_cli_rejects_duplicate_json_keys_and_non_finite_json(self):
        for contents in ['{"id":"first","id":"second"}\n', '{"amount":NaN}\n']:
            result = self.run_cli(contents)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("invalid JSON", result.stderr)

    def test_cli_rejects_unknown_variant_reference(self):
        self.row["groups"]["variant_of"] = "missing-original"
        result = self.run_cli(json.dumps(self.row) + "\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unknown variant_of reference", result.stderr)

    def test_cli_rejects_variant_cycles(self):
        second = copy.deepcopy(self.row)
        second.update(id="second-variant", message=self.row["message"] + " Fictional variant.")
        self.row["groups"]["variant_of"] = second["id"]
        second["groups"]["variant_of"] = self.row["id"]
        result = self.run_cli(json.dumps(self.row) + "\n" + json.dumps(second) + "\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cyclic variant_of", result.stderr)


class ReportIntegrityTest(unittest.TestCase):
    def test_each_public_metric_reconciles_with_its_denominator(self):
        data = json.loads(report.DATA.read_text(encoding="utf-8"))
        for system in data["systems"]:
            for version in system["versions"].values():
                for name, scope in version["scopes"].items():
                    for metric in scope.values():
                        self.assertEqual(97 if name == "mixed" else 22, metric["records"])
                        self.assertEqual(metric["suggestions"], metric["correct_suggestions"] + metric["wrong_suggestions"])
                        self.assertEqual(metric["records"], metric["known_category_records"] + metric["source_insufficient_records"])
                        if metric["suggestions"]:
                            self.assertAlmostEqual(metric["correct_suggestions"] / metric["suggestions"], metric["precision_when_suggesting"])
                        else:
                            self.assertIsNone(metric["precision_when_suggesting"])
        self.assertFalse(data["fresh_review"]["scored"])
        self.assertFalse(data["fresh_review"]["human_review_complete"])
        self.assertEqual(309, data["dataset"]["category_candidates"] + data["dataset"]["abstention_candidates"])
        self.assertEqual(309, sum(s["records"] for s in data["splits"].values()) + 7)

    def test_report_is_current_and_contains_no_external_assets_or_private_rows(self):
        rendered = report.build()
        self.assertEqual(rendered, report.OUTPUT.read_text(encoding="utf-8"))
        for token in ["/Users/", "Sorted-private-datasets", '"source_hash":', '"body_sha256":', '"message":']:
            self.assertNotIn(token, rendered)
        self.assertNotRegex(rendered, r"sms_\d+|gmail:[A-Za-z0-9]+")
        class Assets(HTMLParser):
            def __init__(self):
                super().__init__()
                self.external = []
            def handle_starttag(self, tag, attrs):
                attrs = dict(attrs)
                if tag in {"script", "img", "iframe"} and attrs.get("src"):
                    self.external.append(attrs["src"])
                if tag == "link" and attrs.get("rel") == "stylesheet":
                    self.external.append(attrs.get("href"))
        assets = Assets()
        assets.feed(rendered)
        self.assertEqual([], assets.external)


if __name__ == "__main__":
    unittest.main()
