"""Candidate execution adapter. Expected answers stay in the host controller."""

import copy
import contextlib
import importlib.util
import io
import json
import sys


def observe(module, function, cases):
    results = []
    for case in cases:
        try:
            if case.get("type") == "pages":
                calls = []
                pages = dict(case["pages"])

                def fetch_page(cursor):
                    if cursor in calls:
                        raise AssertionError("Repeated cursor")
                    calls.append(cursor)
                    return copy.deepcopy(pages[cursor])

                actual = getattr(module, function)(fetch_page, case["kind"])
                unchanged = True
            else:
                args = copy.deepcopy(case["args"])
                actual = getattr(module, function)(*args)
                unchanged = args == case["args"]
                calls = None
            json.dumps(actual)  # Invalid return types are candidate failures, not broken infrastructure.
            results.append({"actual": actual, "unchanged": unchanged, "calls": calls})
        except BaseException as exc:
            results.append({"error": type(exc).__name__ + ": " + str(exc)[:300]})
    return results


def compare(cases, observations):
    """Runs in the controller; never execute candidate code in this process."""
    if not isinstance(observations, list) or len(observations) != len(cases):
        raise ValueError("Incomplete candidate observations")
    results = []
    for case, observation in zip(cases, observations):
        error = observation.get("error")
        if not error and observation.get("unchanged") is not True:
            error = "Input was mutated"
        if not error and case.get("type") == "pages" and observation.get("calls") != [p[0] for p in case["pages"]]:
            error = "Incomplete or incorrect traversal"
        if not error and observation.get("actual") != case["expected"]:
            error = "Unexpected result"
        results.append(dict(name=case["name"], passed=not bool(error), **({"error": error} if error else {})))
    return {"passed": all(r["passed"] for r in results), "checks": results}


def grade(module, function, cases):
    # Used only by unit tests for authored fixtures; model candidates run in Docker.
    return compare(cases, observe(module, function, cases))


if __name__ == "__main__":
    with open(sys.argv[2]) as source:
        suite = json.load(source)
    try:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            spec = importlib.util.spec_from_file_location("candidate", sys.argv[1])
            candidate = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(candidate)
            result = observe(candidate, suite["function"], suite["cases"])
    except BaseException as exc:
        result = [{"error": type(exc).__name__ + ": " + str(exc)[:300]} for case in suite["cases"]]
    print(json.dumps(result))
