"""Exercise the recorder wrapper with fake tools; no JVM, listener, or model calls."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "record-demo.sh"


class RecorderCleanupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="black-box-recorder-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.repo = self.root / "repo"
        for name in ("scripts", "src", "target", "docs/assets"):
            (self.repo / name).mkdir(parents=True)
        (self.repo / "pom.xml").touch()
        (self.repo / "scripts/record-demo.sh").write_text(SCRIPT.read_text())
        with zipfile.ZipFile(self.repo / "target/demo.jar", "w") as jar:
            jar.writestr("CaptureDecisionRequest.class", b"fixture")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.run_path = self.root / "run-path"
        self.alive = self.root / "alive"
        self.kills = self.root / "kills"
        self.executable("asciinema", """#!/usr/bin/env python3
import os, pathlib, sys
cast = pathlib.Path(sys.argv[-1])
pathlib.Path(os.environ['FIXTURE_RUN_PATH']).write_text(str(cast.parent))
pathlib.Path(os.environ['FIXTURE_ALIVE']).touch()
(cast.parent / 'retained-database').write_text('fixture')
cast.write_text('the loop just closed\\nRecorder PID 424242\\n')
""")
        self.executable("agg", "#!/bin/sh\nexit 0\n")
        self.executable("lsof", """#!/bin/sh
test -f "$FIXTURE_ALIVE" || exit 1
printf '%s\n' "${FIXTURE_LISTENER:-424242}"
""")
        # Bash builtins are replaced only inside this fixture shell. No actual process is signaled.
        shell_fixture = self.root / "shell-fixture"
        shell_fixture.write_text("""kill() {
  if [[ "$1" == "-0" ]]; then
    [[ -f "$FIXTURE_ALIVE" ]]
  else
    printf '%s\\n' "$*" >> "$FIXTURE_KILLS"
    if [[ "$FIXTURE_SHUTDOWN" == "ok" ]]; then rm "$FIXTURE_ALIVE"; fi
    return 0
  fi
}
sleep() { :; }
""")
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        BASH_ENV=str(shell_fixture), SBA_DEMO_PORT="18888", SBA_DEMO_PACE="1",
                        FIXTURE_RUN_PATH=str(self.run_path), FIXTURE_ALIVE=str(self.alive),
                        FIXTURE_KILLS=str(self.kills), FIXTURE_SHUTDOWN="ok")
        for name in ("SBA_DEMO_GIF", "SBA_DEMO_KEEP_CAST", "FIXTURE_LISTENER"):
            self.env.pop(name, None)
        self.addCleanup(self.remove_run)

    def executable(self, name, body):
        target = self.bin / name
        target.write_text(body)
        target.chmod(0o700)

    def remove_run(self):
        if self.run_path.exists():
            shutil.rmtree(self.run_path.read_text(), ignore_errors=True)

    def run_recorder(self):
        return subprocess.run(["bash", str(self.repo / "scripts/record-demo.sh")],
                              cwd=self.root, env=self.env, text=True, capture_output=True, timeout=15)

    def test_success_confirms_stop_before_removing_private_directory(self):
        result = self.run_recorder()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.kills.read_text().strip(), "424242")
        self.assertFalse(Path(self.run_path.read_text()).exists())

    def test_slow_shutdown_fails_and_preserves_private_directory(self):
        self.env["FIXTURE_SHUTDOWN"] = "slow"
        result = self.run_recorder()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("has not stopped", result.stderr)
        self.assertEqual(self.kills.read_text().strip(), "424242")
        self.assertTrue((Path(self.run_path.read_text()) / "retained-database").exists())

    def test_changed_listener_is_never_signaled(self):
        self.env["FIXTURE_LISTENER"] = "999999"
        result = self.run_recorder()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Cannot confirm ownership", result.stderr)
        self.assertFalse(self.kills.exists())
        self.assertTrue((Path(self.run_path.read_text()) / "retained-database").exists())


if __name__ == "__main__":
    unittest.main()
