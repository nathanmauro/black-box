"""Identical rollback-journal race grader on both pinned revisions.

Run only by the offline qualifier: python -I -S -B <this file> <capture_outbox.py> <report.json>.
It imports the outbox by explicit path, uses only APIs present on both revisions, and writes a
strict per-test JSON report. It never sends, drains, or runs the hook entry point.
"""
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import sqlite3
import stat
import sys
import tempfile
import time
import unittest
from unittest import mock

SUITE = "JournalRaceContract"
ORIGIN = "http://127.0.0.1:9"
FIRST = b'{"fixture":"initial capture"}'
SECOND = b'{"fixture":"concurrent capture"}'
SENTINEL = b"outside sentinel; must never change\n"
DEADLINE_SECONDS = 2.0
outbox = None


class RaceNotEstablished(Exception):
    """The real open/COMMIT/fstat interleaving or a grader-owned step failed: infrastructure,
    never behavior. Grader-owned failures are also recorded on the test, so a candidate that
    catches or converts this exception still cannot turn it into a behavioral result."""


class UnboundedRetry(AssertionError):
    """The candidate kept retrying past its shared deadline."""


REAL_OPEN = os.open
REAL_CLOSE = os.close
REAL_FSTAT = os.fstat


class JournalRaceContract(unittest.TestCase):
    def setUp(self):
        self.base = Path(tempfile.mkdtemp(prefix="journal-race-")).resolve()
        self.addCleanup(shutil.rmtree, self.base, True)
        self.cleanups = []
        self.addCleanup(self.close_all)
        self.journal = outbox.DB_NAME + "-journal"
        self.closed = []
        self.fixture_failures = []

    def close_all(self):
        for close in reversed(self.cleanups):
            try:
                close()
            except Exception:
                pass

    def directory(self, name="outbox"):
        # Queue creates the final directory itself with its private-mode checks.
        return self.base / name

    def queue(self, directory, seconds=DEADLINE_SECONDS):
        queue = outbox.Queue(str(directory), time.monotonic() + seconds)
        self.cleanups.append(queue.close)
        return queue

    def holder(self, directory):
        connection = sqlite3.connect(str(directory / outbox.DB_NAME), timeout=5, isolation_level=None)
        connection.execute("PRAGMA synchronous=OFF")
        self.cleanups.append(connection.close)
        return connection

    def grader_step(self, what, step):
        """Run a grader-owned open/fstat/COMMIT/setup step; any failure is infrastructure."""
        try:
            return step()
        except RaceNotEstablished as error:
            self.fixture_failures.append(what + ": " + str(error))
            raise
        except Exception as error:
            self.fixture_failures.append(what + ": " + type(error).__name__)
            raise RaceNotEstablished(what + " failed: " + type(error).__name__) from None

    def check_fixture(self):
        if self.fixture_failures:
            raise RaceNotEstablished("grader-owned step failed: " + self.fixture_failures[0])

    def begin_write(self, connection, directory):
        def write():
            # A content-preserving header write (the version is already 1; SQLite skips no-op
            # row updates), so SQLite itself creates the rollback journal.
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("PRAGMA user_version=1")
            if not (directory / self.journal).exists():
                raise RaceNotEstablished("second connection did not create a rollback journal")
        self.grader_step("second-connection journal write", write)

    def rows(self, directory):
        connection = sqlite3.connect(str(directory / outbox.DB_NAME), isolation_level=None)
        try:
            return connection.execute(
                """SELECT id, origin, capture_id, event_bytes, sanitizer_version, created_at,
                          logical_bytes, category, attempts, reason FROM captures ORDER BY id""").fetchall()
        finally:
            connection.close()

    def seeded(self, name="outbox"):
        directory = self.directory(name)
        queue = self.queue(directory)
        first = queue.enqueue(ORIGIN, FIRST)
        return directory, queue, first

    def intercept(self, on_open):
        """Patch the real os.open/os.close; on_open(name, descriptor_factory) owns the interleaving."""
        def opened(name, *args, **kwargs):
            return on_open(name, lambda: REAL_OPEN(name, *args, **kwargs))

        def closed(descriptor):
            self.closed.append(descriptor)
            return REAL_CLOSE(descriptor)

        return mock.patch.multiple(outbox.os, open=opened, close=closed)

    def commit_after_open(self, connection, descriptor):
        def commit():
            before = REAL_FSTAT(descriptor)
            connection.execute("COMMIT")
            after = REAL_FSTAT(descriptor)
            if not (before.st_nlink == 1 and after.st_nlink == 0
                    and (before.st_dev, before.st_ino) == (after.st_dev, after.st_ino)):
                raise RaceNotEstablished("opened journal inode did not move from nlink 1 to 0")
            return before.st_ino
        return self.grader_step("commit between open and fstat", commit)

    def open_for_proof(self, open_real):
        # Before the race is proved, the journal exists by construction; failing to open it is ours.
        return self.grader_step("journal open before proof", open_real)

    def track(self, descriptor):
        # Descriptor numbers are reused; only a close after this open counts.
        return descriptor, len(self.closed)

    def assert_closed(self, tracked, message=None):
        descriptor, mark = tracked
        self.assertIn(descriptor, self.closed[mark:], message)

    def candidate(self, action, proof):
        """Run candidate code. Returns (result, error); only a proved race yields a behavioral error.

        Grader-owned failures win over anything the candidate raised or returned. Without proof,
        no outcome (success or any exception) can count as product behavior.
        """
        try:
            result, error = action(), None
        except RaceNotEstablished:
            self.check_fixture()
            raise
        except Exception as raised:  # Every candidate exception, AssertionError included.
            result, error = None, raised
        # One invariant for every candidate outcome: grader failures first, then proof.
        self.check_fixture()
        if not proof:
            raise RaceNotEstablished("candidate finished before the race was established")
        if isinstance(error, AssertionError):
            raise error  # Post-proof assertions (including UnboundedRetry) stay behavioral.
        return result, error

    def accept(self, action, proof):
        result, error = self.candidate(action, proof)
        if isinstance(error, outbox.OutboxError):
            self.fail("committed concurrent journal was rejected: " + str(error))
        if error is not None:
            self.fail("committed concurrent journal failed: " + type(error).__name__)
        return result

    def rejection(self, action, proof):
        result, error = self.candidate(action, proof)
        if isinstance(error, outbox.OutboxError):
            return str(error)
        if error is not None:
            self.fail("rejected without a fixed outbox code: " + type(error).__name__)
        self.fail("unsafe or unbounded file state was accepted")

    def assert_initial_then_second(self, directory, before, first, second):
        rows = self.rows(directory)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0], before[0])
        self.assertNotEqual(second, first)
        self.assertEqual(rows[1][1:5], (ORIGIN, second, SECOND, outbox.SANITIZER_VERSION))
        self.assertEqual(rows[1][6:], (len(SECOND) + len(ORIGIN) + 64, "pending", 0, None))
        self.assertFalse((directory / self.journal).exists())

    def test_commit_between_journal_open_and_check_keeps_capture(self):
        directory, queue, first = self.seeded()
        before = self.rows(directory)
        holder = self.holder(directory)
        self.begin_write(holder, directory)
        proof = []

        def on_open(name, open_real):
            if name != self.journal or proof:
                return open_real()
            descriptor = self.open_for_proof(open_real)
            tracked = self.track(descriptor)
            proof.append((tracked, self.commit_after_open(holder, descriptor)))
            return descriptor

        with self.intercept(on_open):
            second = self.accept(lambda: queue.enqueue(ORIGIN, SECOND), proof)
        if not proof:
            raise RaceNotEstablished("journal was never opened by the candidate")
        self.assert_closed(proof[0][0])
        self.assert_initial_then_second(directory, before, first, second)
        self.assertFalse(queue.db.in_transaction)
        self.assertFalse(holder.in_transaction)

    def test_queue_open_during_concurrent_commit(self):
        directory, seed, first = self.seeded()
        seed.close()
        before = self.rows(directory)
        holder = self.holder(directory)
        self.begin_write(holder, directory)
        proof = []

        def on_open(name, open_real):
            if name != self.journal or proof:
                return open_real()
            descriptor = self.open_for_proof(open_real)
            tracked = self.track(descriptor)
            proof.append((tracked, self.commit_after_open(holder, descriptor)))
            return descriptor

        with self.intercept(on_open):
            queue = self.accept(lambda: self.queue(directory), proof)
        if not proof:
            raise RaceNotEstablished("journal was never opened by the candidate")
        self.assert_closed(proof[0][0])
        self.assertFalse(queue.db.in_transaction)
        second = queue.enqueue(ORIGIN, SECOND)
        self.assert_initial_then_second(directory, before, first, second)
        self.assertFalse(holder.in_transaction)

    def test_safe_replacement_journal_is_rechecked_not_reused(self):
        directory, queue, first = self.seeded()
        before = self.rows(directory)
        holder = self.holder(directory)
        self.begin_write(holder, directory)
        proof = []
        replacement = []

        def on_open(name, open_real):
            if name != self.journal:
                return open_real()
            if not proof:
                descriptor = self.open_for_proof(open_real)
                tracked = self.track(descriptor)
                proof.append((tracked, self.commit_after_open(holder, descriptor)))
                # A genuine new transaction now owns a fresh, safe journal inode.
                self.begin_write(holder, directory)
                return descriptor
            if not replacement:
                self.assert_closed(proof[0][0], "stale descriptor closed before reopening")
                # The replacement exists by construction; failing to open/inspect it is ours.
                descriptor = self.grader_step("replacement journal open", open_real)
                info = self.grader_step("replacement journal fstat", lambda: REAL_FSTAT(descriptor))
                replacement.append((self.track(descriptor), info.st_ino, info.st_nlink))
                return descriptor
            if holder.in_transaction:
                self.grader_step("replacement transaction commit", lambda: holder.execute("COMMIT"))
            return open_real()  # Candidate-owned: the journal is legitimately gone now.

        with self.intercept(on_open):
            second = self.accept(lambda: queue.enqueue(ORIGIN, SECOND), proof)
        if not proof:
            raise RaceNotEstablished("journal was never opened by the candidate")
        self.assertTrue(replacement, "replacement journal was never validated")
        tracked, inode, links = replacement[0]
        self.assertNotEqual(inode, proof[0][1])
        self.assertEqual(links, 1)
        self.assert_closed(tracked)
        self.assertFalse(holder.in_transaction)
        self.assert_initial_then_second(directory, before, first, second)
        self.assertFalse(queue.db.in_transaction)

    def test_ordinary_enqueue_preserved(self):
        directory, queue, first = self.seeded()
        before = self.rows(directory)
        second = queue.enqueue(ORIGIN, SECOND)
        self.assertEqual(len(before), 1)
        self.assertEqual(before[0][1:5], (ORIGIN, first, FIRST, outbox.SANITIZER_VERSION))
        self.assertEqual(before[0][6:], (len(FIRST) + len(ORIGIN) + 64, "pending", 0, None))
        self.assert_initial_then_second(directory, before, first, second)
        self.assertFalse(queue.db.in_transaction)

    def test_unsafe_journal_replacement_rejected(self):
        sentinel = self.base / "sentinel"
        sentinel.write_bytes(SENTINEL)
        sentinel.chmod(0o600)
        for kind in ("hardlink", "mode"):
            with self.subTest(kind=kind):
                directory, queue, first = self.seeded("unsafe-" + kind)
                before = self.rows(directory)
                holder = self.holder(directory)
                self.begin_write(holder, directory)
                journal = directory / self.journal
                proof = []
                opened = []

                def replace(kind=kind, journal=journal):
                    if kind == "hardlink":
                        os.link(str(sentinel), str(journal))
                    else:
                        journal.write_bytes(b"not a journal")
                        journal.chmod(0o644)

                def on_open(name, open_real):
                    if name != self.journal:
                        return open_real()
                    if proof:
                        descriptor = open_real()  # Candidate-owned reopen of the replacement.
                        opened.append(self.track(descriptor))
                        return descriptor
                    descriptor = self.open_for_proof(open_real)
                    opened.append(self.track(descriptor))
                    proof.append(self.commit_after_open(holder, descriptor))
                    self.grader_step("unsafe replacement setup", replace)
                    return descriptor

                with self.intercept(on_open):
                    code = self.rejection(lambda: queue.enqueue(ORIGIN, SECOND), proof)
                self.assertEqual(code, "unsafe_file")
                for tracked in opened:
                    self.assert_closed(tracked)
                self.assertEqual(sentinel.read_bytes(), SENTINEL)
                self.assertEqual(stat.S_IMODE(sentinel.stat().st_mode), 0o600)
                journal.unlink()  # Remove only the fixture entry before reading canonical rows.
                self.assertEqual(sentinel.read_bytes(), SENTINEL)
                self.assertFalse(queue.db.in_transaction)
                self.assertFalse(holder.in_transaction)
                self.assertEqual(self.rows(directory), before)

    def test_unlinked_database_and_sender_lock_still_rejected(self):
        for name in (outbox.DB_NAME, outbox.LOCK_NAME):
            with self.subTest(name=name):
                directory, queue, first = self.seeded("unlinked-" + name)
                before = self.rows(directory)
                if name == outbox.LOCK_NAME:
                    REAL_CLOSE(REAL_OPEN(str(directory / name), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600))
                    (directory / name).chmod(0o600)
                proof = []

                def on_open(opened_name, open_real, name=name, directory=directory):
                    if opened_name != name or proof:
                        return open_real()
                    descriptor = self.grader_step("entry open before proof", open_real)

                    def unlink():
                        before_stat = REAL_FSTAT(descriptor)
                        os.unlink(str(directory / name))
                        after_stat = REAL_FSTAT(descriptor)
                        if not (before_stat.st_nlink == 1 and after_stat.st_nlink == 0):
                            raise RaceNotEstablished("opened entry did not move from nlink 1 to 0")
                    self.grader_step("unlink between open and fstat", unlink)
                    proof.append(self.track(descriptor))
                    return descriptor

                with self.intercept(on_open):
                    code = self.rejection(lambda: queue.enqueue(ORIGIN, SECOND), proof)
                self.assertEqual(code, "unsafe_file")
                self.assert_closed(proof[0])
                self.assertFalse((directory / name).exists())
                self.assertFalse(queue.db.in_transaction)
                if name == outbox.LOCK_NAME:
                    self.assertEqual(self.rows(directory), before)

    def test_repeated_journal_disappearance_is_bounded_and_closes_descriptors(self):
        directory = self.directory()
        started = time.monotonic()
        queue = self.queue(directory, seconds=1.0)
        deadline = started + 1.0
        queue.enqueue(ORIGIN, FIRST)
        before = self.rows(directory)
        holder = self.holder(directory)
        opened = []

        def on_open(name, open_real):
            if name != self.journal:
                return open_real()
            if time.monotonic() > deadline + 1.0:
                raise UnboundedRetry("journal retries continued past the shared deadline")
            if not holder.in_transaction:
                self.begin_write(holder, directory)
            descriptor = self.open_for_proof(open_real)  # Recreated just above, by construction.
            opened.append(self.track(descriptor))
            self.commit_after_open(holder, descriptor)
            return descriptor

        with self.intercept(on_open):
            self.rejection(lambda: queue.enqueue(ORIGIN, SECOND), opened)
        finished = time.monotonic()
        self.assertLessEqual(finished, deadline + 0.5)
        for tracked in opened:
            self.assert_closed(tracked)
        self.assertFalse(holder.in_transaction)
        self.assertFalse(queue.db.in_transaction)
        self.assertEqual(self.rows(directory), before)


class Recorder(unittest.TestResult):
    def __init__(self):
        super().__init__()
        self.cases = {}

    def outcome(self, test, outcome, error=None):
        name = test.id().rsplit(".", 1)[-1]
        current = self.cases.get(name)
        if current is not None and current["outcome"] in ("error", "failure"):
            return  # Keep the first non-pass result of a test and its subtests.
        self.cases[name] = {"name": name, "outcome": outcome,
                            "type": None if error is None else error[0].__name__}

    def addSuccess(self, test):
        if test.id().rsplit(".", 1)[-1] not in self.cases:
            self.outcome(test, "pass")

    def addFailure(self, test, err):
        self.outcome(test, "failure", err)

    def addError(self, test, err):
        self.outcome(test, "error", err)

    def addSkip(self, test, reason):
        self.outcome(test, "skipped")

    def addExpectedFailure(self, test, err):
        self.outcome(test, "error", err)

    def addUnexpectedSuccess(self, test):
        self.outcome(test, "error")

    def addSubTest(self, test, subtest, err):
        if err is not None:
            failed = issubclass(err[0], test.failureException)
            self.outcome(test, "failure" if failed else "error", err)


def runtime():
    return {"python": ".".join(str(part) for part in sys.version_info[:3]),
            "sqlite": sqlite3.sqlite_version, "platform": platform.system() + "-" + platform.machine()}


def main(argv):
    global outbox
    if len(argv) != 2:
        return 2
    source, report = Path(argv[0]), Path(argv[1])
    spec = importlib.util.spec_from_file_location("capture_outbox_under_test", str(source))
    outbox = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(outbox)
    result = Recorder()
    unittest.defaultTestLoader.loadTestsFromTestCase(JournalRaceContract).run(result)
    cases = [result.cases[name] for name in sorted(result.cases)]
    with open(str(report), "x", encoding="utf-8") as output:
        json.dump({"suite": SUITE, "runtime": runtime(), "cases": cases}, output, sort_keys=True)
    return 0 if cases and all(case["outcome"] == "pass" for case in cases) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
