"""Exercise the live demo's safety guards with fake tools; no service or model calls."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "live-demo.sh"
SEED = Path(__file__).resolve().parent / "live-demo-seed.json"
POM = """<project>
  <parent><artifactId>spring-boot-starter-parent</artifactId><version>3.0.0</version></parent>
  <artifactId>sba-agentic</artifactId>
  <version>9.9.9</version>
</project>
"""


class LiveDemoGuardTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="black-box-live-demo-test-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.repo = self.root / "repo"
        (self.repo / "scripts/demo").mkdir(parents=True)
        (self.repo / "src").mkdir()
        (self.repo / "target").mkdir()
        (self.repo / "pom.xml").write_text(POM)
        shutil.copy(SCRIPT, self.repo / "scripts/live-demo.sh")
        shutil.copy(SEED, self.repo / "scripts/demo/live-demo-seed.json")
        self.java_record = self.root / "java.json"
        self.mvn_record = self.root / "mvn.txt"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable(
            "java",
            "#!/usr/bin/env python3\nimport json, pathlib, os, sys\n"
            f"pathlib.Path({str(self.java_record)!r}).write_text(json.dumps("
            "{'env': dict(os.environ), 'args': sys.argv[1:]}))\nsys.exit(23)\n",
        )
        self.executable("curl", "#!/bin/sh\nexit 7\n")
        self.executable("lsof", "#!/bin/sh\nexit 1\n")
        self.executable("mvn", f'#!/bin/sh\necho "$@" > {self.mvn_record}\nexit 1\n')
        self.env = dict(
            os.environ,
            PATH=f"{self.bin}:{os.environ['PATH']}",
            TMPDIR=str(self.root),
            SBA_DEMO_PORT="18989",
        )

    def executable(self, name, body):
        target = self.bin / name
        target.write_text(body)
        target.chmod(0o700)

    def add_current_jar(self, name):
        jar = self.repo / "target" / name
        jar.write_bytes(b"fixture")
        os.utime(jar, (2_000_000_000, 2_000_000_000))
        return jar

    def run_demo(self):
        return subprocess.run(
            ["bash", str(self.repo / "scripts/live-demo.sh")],
            cwd=self.root,
            env=self.env,
            capture_output=True,
            text=True,
            timeout=15,
        )

    def test_refuses_the_installed_recorder_port(self):
        self.env["SBA_DEMO_PORT"] = "8766"

        result = self.run_demo()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Port 8766 belongs to an installed recorder", result.stderr)
        self.assertFalse(self.java_record.exists())

    def test_refuses_a_busy_port(self):
        self.executable("lsof", "#!/bin/sh\necho 4242\n")

        result = self.run_demo()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Port 18989 is in use", result.stderr)
        self.assertFalse(self.java_record.exists())

    def test_runs_the_project_version_jar_with_an_isolated_environment(self):
        self.add_current_jar("sba-agentic-0.1.0.jar")
        jar = self.add_current_jar("sba-agentic-9.9.9.jar")
        self.env.update(
            {
                "SPRING_APPLICATION_JSON": '{"spring":{"datasource":{"url":"jdbc:sqlite:decoy.db"}}}',
                "SPRING_CONFIG_IMPORT": "file:decoy.properties",
                "SBA_DATASOURCE_URL": "jdbc:sqlite:decoy.db",
                "SBA_JUDGE_API_KEY": "decoy",
                "JAVA_TOOL_OPTIONS": "-Dserver.port=8766",
            }
        )

        result = self.run_demo()

        self.assertNotEqual(result.returncode, 0)
        record = json.loads(self.java_record.read_text())
        scratch = record["env"]["HOME"]
        self.assertTrue(scratch.startswith(str(self.root)))
        self.assertEqual(record["args"][:2], ["-jar", str(jar)])
        self.assertIn("--spring.config.location=classpath:/application.yml", record["args"])
        self.assertEqual(record["env"]["SBA_DATASOURCE_URL"], f"jdbc:sqlite:{scratch}/demo.db")
        self.assertEqual(record["env"]["SBA_PORT"], "18989")
        self.assertEqual(record["env"]["SBA_MEMORY_EMBEDDING_ENABLED"], "false")
        for name in (
            "SPRING_APPLICATION_JSON",
            "SPRING_CONFIG_IMPORT",
            "SBA_JUDGE_API_KEY",
            "JAVA_TOOL_OPTIONS",
        ):
            self.assertNotIn(name, record["env"])

    def test_stale_jar_is_rebuilt_without_clean(self):
        older = self.repo / "target/sba-agentic-0.1.0.jar"
        older.write_bytes(b"held by an installed service")

        result = self.run_demo()

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.mvn_record.read_text().split(), ["-q", "-DskipTests", "package"])
        self.assertTrue(older.exists())

    def test_refuses_to_rebuild_a_jar_held_by_a_process(self):
        jar = self.repo / "target/sba-agentic-9.9.9.jar"
        jar.write_bytes(b"held")
        os.utime(jar, (1_000_000_000, 1_000_000_000))
        self.executable(
            "lsof",
            '#!/bin/sh\nfor arg in "$@"; do case "$arg" in *.jar) echo 4242;; esac; done\nexit 0\n',
        )

        result = self.run_demo()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("A running process holds", result.stderr)
        self.assertFalse(self.mvn_record.exists())


if __name__ == "__main__":
    unittest.main()
