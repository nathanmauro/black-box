#!/usr/bin/env python3
"""Explicit live recovery test for this integration only (not a pytest test)."""
import json
import os
import re
import signal
import subprocess
import time

from manage import ROOT, launchctl, load_config, probe, start, stop, target


def pid():
    result = launchctl("print", target("gateway"))
    match = re.search(r"\bpid = (\d+)", result.stdout)
    return int(match[1]) if match else None


def exists(process):
    try:
        os.kill(process, 0)
        return True
    except ProcessLookupError:
        return False


def wait_ready(old=None, old_children=()):
    port = load_config()["port"]
    for _ in range(90):
        current = pid()
        if current and current != old and not any(exists(p) for p in old_children) and probe(port, "/readyz"):
            return current
        time.sleep(.5)
    raise RuntimeError("Service did not recover within 45 seconds")


def main():
    before = wait_ready()
    stop("gateway")
    assert pid() is None and not probe(load_config()["port"], "/healthz")
    start("gateway")
    restarted = wait_ready(before)
    children = [int(x) for x in subprocess.run(["pgrep", "-P", str(restarted)],
                                               capture_output=True, text=True).stdout.split()]
    assert children, "Expected a supervised gateway child"
    os.kill(restarted, signal.SIGKILL)
    recovered = wait_ready(restarted, children)
    report = {"graceful_restart": "passed", "supervisor_sigkill_recovery": "passed",
              "old_children_reaped": True, "supervisor_before": before,
              "supervisor_after_restart": restarted, "supervisor_after_crash": recovered}
    (ROOT / "recovery-proof.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
