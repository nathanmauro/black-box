#!/usr/bin/env python3
"""Compare actual hook paths with synthetic input, a fake curl, and a loopback acknowledgement."""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class Recorder(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.event = body["event"]
        response = json.dumps(
            {
                "captureId": body["captureId"],
                "eventId": str(uuid.uuid4()),
                "sessionId": str(uuid.uuid4()),
                "replayed": False,
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)


def baseline_hook(reference, directory):
    directory.mkdir()
    for name in ("sba-agent-hook.sh", "capture_outbox.py"):
        source = subprocess.check_output(
            ["git", "show", reference + ":scripts/hooks/" + name], cwd=ROOT
        )
        destination = directory / name
        destination.write_bytes(source)
        destination.chmod(0o700)

    return directory / "sba-agent-hook.sh"


def run_hook(hook, payload, durable, directory, server, count=False):
    capture = directory / "capture.json"
    audit = directory / "jq-count.txt"
    capture.unlink(missing_ok=True)
    audit.write_text("")
    server.event = None
    path = str(directory / "bin") + os.pathsep + os.environ["PATH"]
    if count:
        path = str(directory / "counting-bin") + os.pathsep + path
    env = {
        "PATH": path,
        "HOME": str(directory),
        "TMPDIR": str(directory),
        "SBA_AGENT_SOURCE": "claude",
        "SBA_AGENTIC_URL": "http://127.0.0.1:" + str(server.server_port),
        "SBA_CAPTURE_DURABLE": "1" if durable else "0",
        "SBA_CAPTURE_OUTBOX_DIR": str(directory / "outbox"),
        "HOOK_BENCHMARK_CAPTURE": str(capture),
        "HOOK_BENCHMARK_JQ_COUNT": str(audit),
        "HOOK_BENCHMARK_JQ": shutil.which("jq"),
    }
    started = time.perf_counter()
    result = subprocess.run(
        [str(hook), "claude"],
        input=payload,
        capture_output=True,
        cwd=ROOT,
        env=env,
        timeout=10,
        check=True,
    )
    elapsed = (time.perf_counter() - started) * 1000
    if result.stdout or result.stderr:
        raise RuntimeError("Synthetic hook emitted diagnostics: " + repr(result.stderr))
    event = server.event if durable else json.loads(capture.read_text())
    if event is None:
        raise RuntimeError("Synthetic capture was not delivered")
    event.pop("observedAt", None)

    return elapsed, event, len(audit.read_text().splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", required=True)
    parser.add_argument("--runs", type=int, default=12)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    with tempfile.TemporaryDirectory(prefix="black-box-hook-benchmark-") as temporary:
        directory = Path(temporary).resolve()
        baseline = baseline_hook(args.baseline_ref, directory / "baseline")
        hooks = {"baseline": baseline, "candidate": ROOT / "scripts/hooks/sba-agent-hook.sh"}
        fake_bin = directory / "bin"
        fake_bin.mkdir()
        curl = fake_bin / "curl"
        curl.write_text('#!/usr/bin/env bash\ncat > "$HOOK_BENCHMARK_CAPTURE"\n')
        curl.chmod(0o700)
        counting_bin = directory / "counting-bin"
        counting_bin.mkdir()
        jq = counting_bin / "jq"
        jq.write_text(
            "#!/usr/bin/env bash\n"
            'printf "jq\\n" >> "$HOOK_BENCHMARK_JQ_COUNT"\n'
            'exec "$HOOK_BENCHMARK_JQ" "$@"\n'
        )
        jq.chmod(0o700)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Recorder)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            measurements = []
            for durable in (False, True):
                for text_bytes in (100, 512 * 1024):
                    payload = json.dumps(
                        {
                            "session_id": "benchmark-session",
                            "hook_event_name": "Stop",
                            "cwd": "/fixture/hook-benchmark",
                            "last_assistant_message": "x" * text_bytes,
                        }
                    ).encode()
                    timings = {name: [] for name in hooks}
                    counts = {}
                    expected = None
                    for name, hook in hooks.items():
                        _, event, counts[name] = run_hook(
                            hook, payload, durable, directory, server, count=True
                        )
                        if expected is None:
                            expected = event
                        elif event != expected:
                            raise RuntimeError("Baseline and candidate capture differ")
                    # Instrumentation is disabled for timing. Alternate order to reduce warm-cache bias.
                    for run in range(args.runs + 2):
                        order = list(hooks) if run % 2 else list(reversed(hooks))
                        for name in order:
                            elapsed, event, _ = run_hook(
                                hooks[name], payload, durable, directory, server
                            )
                            if event != expected:
                                raise RuntimeError("Repeated synthetic capture changed")
                            if run >= 2:
                                timings[name].append(elapsed)
                    measurements.append(
                        {
                            "mode": "durable" if durable else "direct",
                            "text_bytes": text_bytes,
                            "runs": args.runs,
                            "jq_processes": counts,
                            "median_ms": {
                                name: round(statistics.median(values), 3)
                                for name, values in timings.items()
                            },
                        }
                    )
            print(json.dumps(measurements, indent=2))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    main()
