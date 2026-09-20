"""Authored, synthetic tasks. Hidden cases and reference solutions never enter workers."""

import random


TASKS = ("pagination", "rounding", "changed-contract")


def task(name, seed):
    rng = random.Random(seed)
    suffix = str(rng.randrange(10000, 99999))
    if name == "pagination":
        spec = """Implement collect_pages(fetch_page, kind).
fetch_page(cursor) returns {"items": [{"id": ..., "kind": ...}], "next_cursor": ...}.
Start with cursor None; return matching item IDs in encounter order. Continue until
next_cursor is None. Cursors are opaque strings (including "0" or ""); never parse
or increment them. A page with no matching items, no items, or few items can still
have a continuation. Every provided cursor is valid and the chain is finite.
"""
        broken = '''def collect_pages(fetch_page, kind):
    cursor, result = None, []
    while True:
        page = fetch_page(cursor)
        matches = [x["id"] for x in page["items"] if x["kind"] == kind]
        result.extend(matches)
        if not matches or page["next_cursor"] is None:
            return result
        cursor = page["next_cursor"]
'''
        reference = broken.replace('if not matches or page["next_cursor"] is None:',
                                   'if page["next_cursor"] is None:')
        cases = []
        for label, middle, cursor in [
            ("unmatched-page", [{"id": "noise", "kind": "other"}], "after-" + suffix),
            ("empty-page", [], "gap-" + suffix),
            ("zero-cursor", [], "0"),
            ("empty-string-cursor", [], ""),
        ]:
            cases.append({"name": label, "type": "pages", "kind": "wanted",
                          "pages": [[None, {"items": middle, "next_cursor": cursor}],
                                    [cursor, {"items": [{"id": suffix, "kind": "wanted"}],
                                              "next_cursor": None}]],
                          "expected": [suffix]})
        public = [{"name": "single-page", "type": "pages", "kind": "wanted",
                   "pages": [[None, {"items": [{"id": "a", "kind": "wanted"}],
                                      "next_cursor": None}]], "expected": ["a"]}]
        lesson = "An earlier fixture stopped at an empty filtered page and missed later matches."
        rationale = "Measured failure: continuation follows next_cursor, not filtered item count."
        alternatives = ["Stop after a page has no matching items"]
        historical = cases
        historical_reference = reference
        historical_broken = broken
    elif name == "rounding":
        spec = """Implement invoice_total(lines) returning a fixed two-decimal string.
Each line has a decimal string unit_price and an integer quantity. Prices can be
negative (credits). Multiply price by quantity exactly; round EACH LINE to cents
with ROUND_HALF_UP (ties away from zero), then sum the rounded lines. Empty input
returns "0.00". Use only the Python standard library.
"""
        broken = '''def invoice_total(lines):
    return f'{sum(float(x["unit_price"]) * x["quantity"] for x in lines):.2f}'
'''
        reference = '''from decimal import Decimal, ROUND_HALF_UP
def invoice_total(lines):
    total = sum((Decimal(x["unit_price"]) * x["quantity"]).quantize(
        Decimal("0.01"), rounding=ROUND_HALF_UP) for x in lines)
    return format(total, ".2f")
'''
        quantity = rng.randrange(2, 9)
        cases = [
            {"name": "positive-tie", "args": [[{"unit_price": "1.005", "quantity": 1}]],
             "expected": "1.01"},
            {"name": "negative-tie", "args": [[{"unit_price": "-1.005", "quantity": 1}]],
             "expected": "-1.01"},
            {"name": "round-per-line", "args": [[{"unit_price": "0.005", "quantity": 1}] * quantity],
             "expected": f"0.{quantity:02d}"},
            {"name": "multiply-before-round", "args": [[{"unit_price": "0.004", "quantity": 3}]],
             "expected": "0.01"},
            {"name": "large-exact-decimal", "args": [[{"unit_price": "123456789012345.005", "quantity": 1}]],
             "expected": "123456789012345.01"},
            {"name": "empty-invoice", "args": [[]], "expected": "0.00"},
        ]
        public = [{"name": "whole-price", "args": [[{"unit_price": "2.00", "quantity": 3}]],
                   "expected": "6.00"}]
        lesson = "An earlier billing fixture failed decimal ties and per-line rounding."
        rationale = "Measured failure: binary floats and rounding only the final sum violate invoice v1."
        alternatives = ["Use float and round the final total"]
        historical = cases
        historical_reference = reference
        historical_broken = broken
    elif name == "changed-contract":
        spec = """Implement deduplicate(events) for CURRENT CONTRACT v2.
Each event has tenant, id, and payload. An identity is the PAIR (tenant, id).
Keep the first event for each identity, in input order, unchanged. IDs may repeat
across tenants and those events must all survive. Input must not be mutated.
Historical v1 advice used globally unique id alone; that contract no longer applies.
"""
        broken = '''def deduplicate(events):
    seen, result = set(), []
    for event in events:
        key = event["id"]
        if key not in seen:
            seen.add(key)
            result.append(event)
    return result
'''
        reference = broken.replace('key = event["id"]', 'key = (event["tenant"], event["id"])')
        first = {"tenant": "east-" + suffix, "id": "shared", "payload": "first"}
        second = {"tenant": "west-" + suffix, "id": "shared", "payload": "second"}
        duplicate = dict(first, payload="later")
        cases = [
            {"name": "cross-tenant-identity", "args": [[first, second]], "expected": [first, second]},
            {"name": "keep-first-per-tenant", "args": [[first, duplicate, second]], "expected": [first, second]},
            {"name": "preserve-order", "args": [[second, first, duplicate]], "expected": [second, first]},
            {"name": "empty-events", "args": [[]], "expected": []},
        ]
        public = [{"name": "same-tenant-duplicate", "args": [[first, duplicate]], "expected": [first]}]
        lesson = "Historical CONTRACT v1: deduplicate by globally unique event id."
        rationale = ("The v1 fixture required suppressing duplicate IDs even across tenants. "
                     "This is historical evidence for v1 only; requirements may change.")
        alternatives = ["Use a tenant/id pair under the old globally unique v1 contract"]
        historical = [{"name": "v1-global-identity", "args": [[first, second]], "expected": [first]}]
        historical_reference = broken
        historical_broken = reference
    else:
        raise ValueError("Unknown task: " + name)
    function = {"pagination": "collect_pages", "rounding": "invoice_total",
                "changed-contract": "deduplicate"}[name]
    return {"name": name, "spec": spec, "starter": broken, "reference": reference,
            "function": function, "cases": public + cases, "public": public,
            "historical_cases": historical, "historical_reference": historical_reference,
            "historical_broken": historical_broken, "lesson": lesson,
            "rationale": rationale, "alternatives": alternatives}
