"""Exercise the real demo launcher with fake Java; no service or model calls."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "demo.sh"


class DemoIsolationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="black-box-demo-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.repo = self.root / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "src").mkdir()
        (self.repo / "target").mkdir()
        (self.repo / "pom.xml").touch()
        # Confine the old launcher's fixed scratch paths for the pre-fix reproduction.
        script = SCRIPT.read_text().replace("/tmp/black-box-demo.db", str(self.root / "old-demo.db"))
        script = script.replace("/tmp/black-box-demo.log", str(self.root / "old-demo.log"))
        (self.repo / "scripts/demo.sh").write_text(script)
        with zipfile.ZipFile(self.repo / "target/demo.jar", "w") as jar:
            jar.writestr("CaptureDecisionRequest.class", b"fixture")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.record = self.root / "java.json"
        self.executable("java", "#!/usr/bin/env python3\nimport json, os, pathlib, sys\n"
                        f"pathlib.Path({str(self.record)!r}).write_text(json.dumps("
                        "{'env': dict(os.environ), 'cwd': os.getcwd(), 'args': sys.argv[1:]}))\n"
                        "sys.exit(23)\n")
        self.executable("curl", "#!/bin/sh\nexit 7\n")
        self.executable("lsof", "#!/bin/sh\nexit 1\n")
        self.executable("open", "#!/bin/sh\nexit 91\n")
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        TMPDIR=str(self.root), SBA_DEMO_PORT="18988", SBA_DEMO_NO_OPEN="1")

    def executable(self, name, body):
        target = self.bin / name
        target.write_text(body)
        target.chmod(0o700)

    def run_demo(self):
        return subprocess.run(["bash", str(self.repo / "scripts/demo.sh")], cwd=self.root,
                              env=self.env, capture_output=True, text=True, timeout=15)

    def test_runtime_is_isolated_from_ambient_configuration(self):
        poison = {
            "SPRING_APPLICATION_JSON": '{"spring":{"datasource":{"url":"jdbc:sqlite:decoy.db"}}}',
            "SPRING_CONFIG_LOCATION": "file:application.yml",
            "SPRING_PROFILES_ACTIVE": "postgres",
            "SPRING_DATASOURCE_URL": "jdbc:sqlite:decoy.db",
            "JAVA_TOOL_OPTIONS": "-Dspring.datasource.url=jdbc:sqlite:decoy.db",
            "JDK_JAVA_OPTIONS": "-Dserver.port=8766",
            "_JAVA_OPTIONS": "-Dsba.judge.enabled=true",
            "SBA_SUMMARY_BACKEND": "external",
            "SBA_SUMMARY_EXTERNAL_COMMAND": "must-not-run",
            "SBA_JUDGE_ENABLED": "true",
            "SBA_MEMORY_EMBEDDING_ENABLED": "true",
            "SBA_ASK_EMBEDDING_ENABLED": "true",
            "SBA_EXPORT_OBSIDIAN_DIR": str(self.root / "must-not-export"),
            "SBA_BIND_ADDRESS": "0.0.0.0",
            "SBA_DATASOURCE_URL": "jdbc:sqlite:decoy.db",
            "SBA_RETIRE_WORKFLOW": "true",
        }
        self.env.update(poison)
        (self.root / "application.yml").write_text("spring.datasource.url: jdbc:sqlite:decoy.db\n")
        result = self.run_demo()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        actual = json.loads(self.record.read_text())
        for name in poison:
            self.assertNotEqual(actual["env"].get(name), poison[name], name)
        self.assertEqual(actual["env"]["SBA_SUMMARY_BACKEND"], "local")
        for name in ("SBA_LOCAL_AI_ENABLED", "SBA_MEMORY_EMBEDDING_ENABLED",
                     "SBA_ASK_EMBEDDING_ENABLED", "SBA_JUDGE_ENABLED", "SBA_ELASTICSEARCH_ENABLED"):
            self.assertEqual(actual["env"][name], "false", name)
        self.assertEqual(actual["env"]["SBA_BIND_ADDRESS"], "127.0.0.1")
        self.assertIn("--spring.config.location=classpath:/application.yml", actual["args"])
        directory = Path(actual["cwd"])
        self.assertTrue(directory.name.startswith("black-box-demo."), directory)
        self.assertEqual(directory.parent, self.root)
        self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        self.assertEqual(actual["env"]["SBA_DATASOURCE_URL"], f"jdbc:sqlite:{directory}/demo.db")
        self.assertFalse((self.root / "decoy.db").exists())

    def test_each_invocation_owns_a_new_directory(self):
        self.run_demo()
        first = json.loads(self.record.read_text())["cwd"]
        self.run_demo()
        second = json.loads(self.record.read_text())["cwd"]
        self.assertNotEqual(first, second)

    def test_invalid_port_fails_before_launch(self):
        for port in ("0", "65536", "8766/path", "--help"):
            with self.subTest(port=port):
                self.env["SBA_DEMO_PORT"] = port
                result = self.run_demo()
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.record.exists())

    def test_invalid_pace_fails_before_launch(self):
        for pace in ("-1", "fast", "1;touch x", "11"):
            with self.subTest(pace=pace):
                self.env["SBA_DEMO_PACE"] = pace
                result = self.run_demo()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("SBA_DEMO_PACE", result.stdout + result.stderr)
                self.assertFalse(self.record.exists())

    def test_busy_port_fails_before_launch(self):
        self.executable("lsof", "#!/bin/sh\nprintf '999999\\n'\n")
        result = self.run_demo()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.record.exists())

    def test_port_taken_during_startup_never_receives_seed(self):
        checked = self.root / "port-checked"
        seeded = self.root / "foreign-seed"
        curl_args = self.root / "curl-args.json"
        self.executable("lsof", "#!/bin/sh\n"
                        f"if test -f '{checked}'; then printf '999999\\n'; else touch '{checked}'; fi\n")
        self.executable("java", "#!/usr/bin/env python3\nimport time\ntime.sleep(30)\n")
        self.executable("curl", "#!/usr/bin/env python3\nimport json, pathlib, sys\n"
                        f"pathlib.Path({str(curl_args)!r}).write_text(json.dumps(sys.argv[1:]))\n"
                        "if any('/api/status' in arg for arg in sys.argv):\n"
                        " print('{\"storage\":{\"sessions\":0}}')\n"
                        f"else: pathlib.Path({str(seeded)!r}).touch()\n")
        result = self.run_demo()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refusing to seed", result.stdout + result.stderr)
        self.assertFalse(seeded.exists())
        self.assertEqual(json.loads(curl_args.read_text())[0], "-q")


if __name__ == "__main__":
    unittest.main()
