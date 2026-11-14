#!/usr/bin/env python3
"""Validate public candidate JSONL locally using only the Python standard library."""
import argparse
import datetime
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "data/contributions/transaction-example.schema.json"


def shape_errors(value, schema, root, path="$"):
    """The small JSON Schema subset used by our checked-in schema; no network refs."""
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/"):
            raise ValueError("Only local schema references are supported")
        target = root
        for part in ref[2:].split("/"):
            target = target[part]
        return shape_errors(value, target, root, path)
    types = schema.get("type", [])
    types = [types] if isinstance(types, str) else types
    matches = {
        "object": isinstance(value, dict), "array": isinstance(value, list),
        "string": isinstance(value, str), "boolean": isinstance(value, bool),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "null": value is None,
    }
    if types and not any(matches[t] for t in types):
        return [path + ": incorrect value type"]
    errors = []
    if "enum" in schema and value not in schema["enum"]:
        errors.append(path + ": value is outside the allowed vocabulary")
    if isinstance(value, dict):
        for name in schema.get("required", []):
            if name not in value:
                errors.append(path + "." + name + ": required field missing")
        properties = schema.get("properties", {})
        for name, child in value.items():
            if name in properties:
                errors.extend(shape_errors(child, properties[name], root, path + "." + name))
            elif schema.get("additionalProperties") is False:
                errors.append(path + ": unexpected field")
    elif isinstance(value, list):
        if len(value) < schema.get("minItems", 0):
            errors.append(path + ": too few items")
        if schema.get("uniqueItems") and len({json.dumps(x, sort_keys=True) for x in value}) != len(value):
            errors.append(path + ": duplicate items")
        for i, child in enumerate(value):
            errors.extend(shape_errors(child, schema.get("items", {}), root, path + "[" + str(i) + "]"))
    elif isinstance(value, str):
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", sys.maxsize):
            errors.append(path + ": string length is outside the allowed range")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(path + ": value does not match the required format")
        if schema.get("format") == "date":
            try:
                if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
                    raise ValueError()
                datetime.date.fromisoformat(value)
            except ValueError:
                errors.append(path + ": use a real calendar date in YYYY-MM-DD format")
    elif isinstance(value, (float, int)) and not isinstance(value, bool):
        try:
            finite = math.isfinite(value)
        except OverflowError:
            finite = False
        if not finite:
            errors.append(path + ": number must be finite")
        elif "minimum" in schema and value < schema["minimum"]:
            errors.append(path + ": number is below the allowed minimum")
    return errors


def strings(value):
    if isinstance(value, dict):
        for child in value.values():
            yield from strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from strings(child)
    elif isinstance(value, str):
        yield value


def privacy_errors(row):
    """Conservative red flags only. Passing does not establish anonymity."""
    errors = set()
    for text in strings(row):
        if re.search(r"\d{8,}", text):
            errors.add("possible phone, full account/card, reference, or long numeric identifier; replace it locally")
        if re.search(r"\b[A-Z]{5}\d{4}[A-Z]\b", text):
            errors.add("possible PAN-like identifier; use a non-identifying placeholder")
        if re.search(r"-----BEGIN [^-]*PRIVATE KEY|AIza[0-9A-Za-z_-]{20,}|ya29\.[0-9A-Za-z_-]+|eyJ[A-Za-z0-9_-]{20,}\.", text):
            errors.add("possible credential material; never submit real secrets")
        for match in re.finditer(r"[\w.+-]+@([\w.-]+)", text):
            domain = match.group(1).rstrip(".").casefold()
            if domain not in {"example", "example.com", "example.org", "example.net"} and not domain.endswith(".test"):
                errors.add("possible personal email/UPI identifier; replace it with a reserved example identifier")
        for match in re.finditer(r"https?://[^\s<>]+", text, re.I):
            try:
                host = (urlsplit(match.group(0)).hostname or "").casefold()
            except ValueError:
                errors.add("malformed URL; replace it with a public-safe example.test placeholder")
                continue
            if host not in {"example.com", "example.org", "example.net"} and not host.endswith(".test"):
                errors.add("replace live URLs with an example.test URL; remove tracking or authentication links")
        secret_patterns = [
            r"(?i)\b(?:otp|pin|password|passcode|verification code)\s*(?:is|:|=|-)?\s*(\d{4,8})\b",
            r"(?i)\b(\d{4,8})\s+is\s+(?:your\s+)?(?:otp|pin|password|passcode)\b",
        ]
        for pattern in secret_patterns:
            for match in re.finditer(pattern, text):
                if set(match.group(1)) != {"0"}:
                    errors.add("possible secret code; use OTP_REDACTED or an all-zero fictional code")
    return sorted(errors)


def semantic_errors(row):
    e = row["expected"]
    errors = []
    if e["category_status"] == "category":
        if e["category"] is None:
            errors.append("expected.category: a category decision needs a category")
        if e["category_basis"] not in {"source_explicit", "merchant_policy"}:
            errors.append("expected.category_basis: a category needs source or documented merchant-policy support")
        if e["bank_cash_movement"] != "confirmed" or e["status"] != "completed":
            errors.append("expected.category_status: category-task examples require confirmed completed movement")
    elif e["category"] is not None:
        errors.append("expected.category: use null for abstain or not_applicable")
    if e["category_status"] == "abstain" and e["category_basis"] != "insufficient":
        errors.append("expected.category_basis: an abstention needs insufficient evidence")
    if e["category_status"] == "not_applicable" and e["category_basis"] != "not_applicable":
        errors.append("expected.category_basis: use not_applicable for non-category tasks")
    if e["context_required"] and e["category_status"] == "category":
        errors.append("expected.category_status: private context cannot supply a shared category label")
    if e["bank_cash_movement"] == "confirmed":
        if e["status"] != "completed" or e["direction"] == "unknown":
            errors.append("expected: confirmed movement needs completed status and a known direction")
        if e["amount"] == 0:
            errors.append("expected.bank_cash_movement: a zero-amount event does not confirm positive cash movement")
    if e["amount"] is not None and e["currency"] is None:
        errors.append("expected.currency: state the currency of a known amount")
    if e["transaction_type"] in {"refund", "income", "reward"} and e["direction"] != "credit":
        errors.append("expected.transaction_type: this incoming type needs credit direction")
    if e["category"] == "Refund" and e["transaction_type"] != "refund":
        errors.append("expected.transaction_type: a Refund category needs refund type")
    if e["category"] in {"Refund", "Income", "Reward"} and e["direction"] != "credit":
        errors.append("expected.category: this incoming category needs credit direction")
    if row["groups"]["variant_of"] == row["id"]:
        errors.append("groups.variant_of: an example cannot be its own variant")
    for evidence in row["evidence"]:
        if evidence["text"] not in row["message"]:
            errors.append("evidence: quote must occur literally in the sanitized message")
    review = row["review"]
    if review["status"] == "human_reviewed":
        if review["method"] not in {"human_review", "assistant_then_human"} or not review["fields"]:
            errors.append("review: human review requires a human method and explicitly checked fields")
    elif review["fields"] or review["method"] in {"human_review", "assistant_then_human"}:
        errors.append("review: a draft must not claim human-reviewed fields or a completed review method")
    return errors


def validate_record(row, schema):
    errors = shape_errors(row, schema, schema)
    if not errors:
        errors.extend(semantic_errors(row))
    if isinstance(row, dict):
        errors.extend(privacy_errors(row))
    return errors


def reject_constant(_):
    raise ValueError("Non-finite JSON number")


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="JSONL files or directories. Default: data/contributions")
    args = parser.parse_args()
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    paths = [Path(p) for p in args.paths] or [ROOT / "data/contributions"]
    files = set()
    for path in paths:
        if path.is_dir():
            files.update(path.rglob("*.jsonl"))
        elif path.is_file():
            files.add(path)
        else:
            parser.error("Input path does not exist: " + str(path))
    if not files:
        parser.error("No JSONL files found")
    errors = []
    ids, bodies, variants = {}, {}, {}
    total = 0
    for path in sorted(files):
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (UnicodeError, OSError):
            errors.append(str(path) + ": unreadable UTF-8 file")
            continue
        if not lines:
            errors.append(str(path) + ": empty contribution file")
        for line_number, line in enumerate(lines, 1):
            where = str(path) + ":" + str(line_number)
            if not line.strip():
                errors.append(where + ": blank lines are not allowed in JSONL")
                continue
            try:
                row = json.loads(line, parse_constant=reject_constant, object_pairs_hook=unique_keys)
            except (ValueError, RecursionError):
                errors.append(where + ": invalid JSON object; content omitted from diagnostics")
                continue
            record_errors = validate_record(row, schema)
            errors.extend(where + ": " + error for error in record_errors)
            total += 1
            if record_errors:
                continue
            variants[row["id"]] = row["groups"]["variant_of"]
            for value, seen, kind in [
                (row["id"], ids, "example ID"),
                (hashlib.sha256(row["message"].encode()).hexdigest(), bodies, "message body"),
            ]:
                if value in seen:
                    errors.append(where + ": duplicate " + kind + "; first occurrence: " + seen[value])
                else:
                    seen[value] = where
    for ident, original in variants.items():
        if original and original not in ids:
            errors.append(ids[ident] + ": unknown variant_of reference; include the original file or validate the whole contribution directory")
        visited = {ident}
        while original in variants:
            if original in visited:
                errors.append(ids[ident] + ": cyclic variant_of references")
                break
            visited.add(original)
            original = variants[original]
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print("Validation failed. No data was changed or uploaded.", file=sys.stderr)
        return 1
    print(f"Validated {total} examples in {len(files)} file(s). Structural and red-flag checks only; human privacy/label review is still required.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
