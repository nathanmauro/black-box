"""Prove the demo launchers leave an installed service's jar alone; no JVM or Maven runs."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]
HELPER = SCRIPTS / "demo/recorder-jar.sh"
LAUNCHERS = ("demo.sh", "quickstart.sh", "record-demo.sh")
OLDER_JAR = "sba-agentic-0.1.0.jar"
PROJECT_JAR = "black-box-9.9.9.jar"
POM = """<project>
  <parent><artifactId>spring-boot-starter-parent</artifactId><version>3.0.0</version></parent>
  <artifactId>black-box</artifactId>
  <version>9.9.9</version>
</project>
"""
FUTURE = 2_000_000_000
PAST = 1_000_000_000


class RecorderJarTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="black-box-recorder-jar-test-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.repo = self.root / "repo"
        for name in ("scripts/demo", "src", "target", "docs/assets"):
            (self.repo / name).mkdir(parents=True)
        (self.repo / "pom.xml").write_text(POM)
        os.utime(self.repo / "pom.xml", (PAST + 1, PAST + 1))
        for name in LAUNCHERS:
            shutil.copy(SCRIPTS / name, self.repo / "scripts" / name)
        shutil.copy(HELPER, self.repo / "scripts/demo/recorder-jar.sh")
        self.maven_record = self.root / "maven.txt"
        self.java_record = self.root / "java.json"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        # A real `mvn clean` deletes target/, so the fake does too.
        self.executable(
            "mvn",
            '#!/bin/sh\nif [ "$1" = "-v" ]; then echo "Apache Maven 3.9.9"; exit 0; fi\n'
            f'echo "$@" >> {self.maven_record}\n'
            f'case " $* " in *" clean "*) rm -rf {self.repo}/target;; esac\nexit 1\n',
        )
        self.executable(
            "java",
            "#!/usr/bin/env python3\nimport json, pathlib, sys\n"
            "if sys.argv[1:] == ['-version']:\n"
            "    print('java version \"21.0.1\"', file=sys.stderr)\n"
            "    sys.exit(0)\n"
            f"pathlib.Path({str(self.java_record)!r}).write_text(json.dumps(sys.argv[1:]))\n"
            "sys.exit(23)\n",
        )
        self.executable("curl", "#!/bin/sh\nexit 7\n")
        self.executable("open", "#!/bin/sh\nexit 91\n")
        self.executable("asciinema", "#!/bin/sh\nexit 1\n")
        self.executable("agg", "#!/bin/sh\nexit 0\n")
        self.executable(
            "lsof",
            '#!/bin/sh\nfor arg in "$@"; do\n'
            '  if [ -n "$FIXTURE_HELD_JAR" ] && [ "$arg" = "$FIXTURE_HELD_JAR" ]; then\n'
            "    echo 4242\n    exit 0\n  fi\ndone\nexit 1\n",
        )
        self.env = dict(
            os.environ,
            PATH=f"{self.bin}:{os.environ['PATH']}",
            TMPDIR=str(self.root),
            SBA_DEMO_PORT="18990",
            SBA_DEMO_NO_OPEN="1",
        )
        self.env.pop("FIXTURE_HELD_JAR", None)

    def executable(self, name, body):
        target = self.bin / name
        target.write_text(body)
        target.chmod(0o700)

    def add_jar(self, name, modified):
        jar = self.repo / "target" / name
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("CaptureDecisionRequest.class", b"fixture")
        os.utime(jar, (modified, modified))
        return jar

    def run_launcher(self, name):
        return subprocess.run(
            ["bash", str(self.repo / "scripts" / name)],
            cwd=self.root,
            env=self.env,
            capture_output=True,
            text=True,
            timeout=15,
        )

    def test_stale_project_jar_is_rebuilt_without_deleting_the_older_jar(self):
        for name in LAUNCHERS:
            with self.subTest(launcher=name):
                self.maven_record.unlink(missing_ok=True)
                older = self.add_jar(OLDER_JAR, FUTURE)
                self.add_jar(PROJECT_JAR, PAST)

                self.run_launcher(name)

                self.assertEqual(self.maven_record.read_text().split(), ["-q", "-DskipTests", "package"])
                self.assertTrue(older.exists(), "the build deleted the older jar")

    def test_a_current_older_jar_never_stands_in_for_the_project_jar(self):
        for name in LAUNCHERS:
            with self.subTest(launcher=name):
                self.maven_record.unlink(missing_ok=True)
                self.add_jar(OLDER_JAR, FUTURE)

                self.run_launcher(name)

                self.assertEqual(self.maven_record.read_text().split(), ["-q", "-DskipTests", "package"])

    def test_refuses_to_rebuild_a_jar_held_by_a_process(self):
        for name in LAUNCHERS:
            with self.subTest(launcher=name):
                held = self.add_jar(PROJECT_JAR, PAST)
                self.env["FIXTURE_HELD_JAR"] = str(held)

                result = self.run_launcher(name)

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("A running process holds", result.stdout + result.stderr)
                self.assertFalse(self.maven_record.exists())

    def test_demo_launches_the_project_jar_when_an_older_one_sorts_first(self):
        self.add_jar(OLDER_JAR, FUTURE)
        jar = self.add_jar(PROJECT_JAR, FUTURE)

        self.run_launcher("demo.sh")

        self.assertEqual(json.loads(self.java_record.read_text())[:2], ["-jar", str(jar)])
        self.assertFalse(self.maven_record.exists())

    def test_unreadable_project_version_stops_before_any_build(self):
        (self.repo / "pom.xml").write_text("<project/>")

        result = self.run_launcher("demo.sh")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("project version", result.stdout + result.stderr)
        self.assertFalse(self.maven_record.exists())


if __name__ == "__main__":
    unittest.main()
