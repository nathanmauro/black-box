#!/usr/bin/env python3
"""Serve the history_search contract from a private canonical compact-search server (NAT-319).

Loads one hash-pinned frozen corpus, launches the explicitly supplied hash-pinned JAR inside a
private temporary root (compact_server.PrivateCompactServer), proves stored-row fidelity, then runs
the same stream loop and Session budgets as history_search.py serve. Development infrastructure:
no model runner, provider call or efficacy claim. Exit 2: controller error before any delivery;
exit 3: any failure after the handoff was delivered, mid-session or during cleanup (the pair is
invalid; nothing further was delivered).
"""

import argparse
import sys

import compact_server as cs
import history_search as h


def serve(args, stdin, stdout, stderr):
    try:
        corpus = h.load_corpus(args.manifest, args.manifest_sha256)
    except h.CorpusError as error:
        h._host(stderr, {"controller_error": {"code": error.code, "message": error.message}})
        return 2
    session = None
    server = None
    try:
        server = cs.PrivateCompactServer(args.jar, args.jar_sha256, args.java, corpus)
        with server:
            session = h.Session(corpus, server.backend())
            h.stream(session, stdin, stdout, stderr)
            h._host(stderr, {"compact_backend": session.backend.host_log})
    except cs.ControllerError as error:
        # After Session.start the model has received bytes, so any later controller failure (cleanup
        # included) invalidates the pair: exit 3, never the pre-delivery exit 2.
        delivered = session is not None and session.started
        record = {"code": error.code, "message": error.message,
                  "phase": "cleanup" if delivered else "before_start", "pair_invalid": delivered}
        h._host(stderr, {"controller_error": record})
        if server is not None:
            h._host(stderr, {"compact_server": server.report})
        return 3 if delivered else 2
    h._host(stderr, {"compact_server": server.report})
    return 3 if "infrastructure_error" in session.accounting() else 0


def main(argv=None, stdin=None, stdout=None, stderr=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("serve", help="Emit the handoff, then answer JSONL {\"query\": ...} lines.")
    command.add_argument("--manifest", required=True, help="Frozen corpus manifest JSON file.")
    command.add_argument("--manifest-sha256", required=True, help="Exact registered manifest SHA-256.")
    command.add_argument("--jar", required=True, help="Absolute path of the trusted packaged Black Box JAR.")
    command.add_argument("--jar-sha256", required=True, help="Exact SHA-256 of that JAR.")
    command.add_argument("--java", required=True, help="Absolute path of a Java 21 executable.")
    args = parser.parse_args(argv)
    return serve(args, stdin or sys.stdin.buffer, stdout or sys.stdout.buffer, stderr or sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
