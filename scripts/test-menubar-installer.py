#!/usr/bin/env python3
"""Exercise the real installer against isolated targets and inert service shims."""
import hashlib
import os
from pathlib import Path
import plistlib
import shlex
import subprocess
import tempfile

repo = Path(__file__).resolve().parent.parent
source_app = repo / "target/macos/Black Box Recall.app"
assert source_app.is_dir(), "Build the helper first"

with tempfile.TemporaryDirectory(prefix="blackbox-installer-fixture-") as directory:
    root = Path(directory)
    apps = root / "Applications"
    agents = root / "Library/LaunchAgents"
    apps.mkdir()
    agents.mkdir(parents=True)
    binary_dir = root / "bin"
    binary_dir.mkdir()
    agent = agents / "dev.blackbox.quick-recall.plist"
    destination = apps / "Black Box Recall.app"
    executable = destination / "Contents/MacOS/BlackBoxRecall"
    service_log = root / "service-operations.txt"

    def script(path, text):
        path.write_text("#!/bin/bash\nset -euo pipefail\n" + text)
        path.chmod(0o755)

    script(binary_dir / "pgrep", "exit 1\n")
    script(binary_dir / "codesign", "exit 0\n")
    script(binary_dir / "launchctl", f'''if [[ "$1" == print ]]; then printf 'pid = {os.getpid()}\\n'; else printf '%s\\n' "$1" >> {shlex.quote(str(service_log))}; fi\n''')

    installer = (repo / "scripts/install-menubar.sh").read_text()
    replacements = {
        'repo_dir="$(cd "$(dirname "$0")/.." && pwd)"': "repo_dir=" + shlex.quote(str(repo)),
        'applications_dir="$HOME/Applications"': "applications_dir=" + shlex.quote(str(apps)),
        'agent_dir="$HOME/Library/LaunchAgents"': "agent_dir=" + shlex.quote(str(agents)),
    }
    for old, new in replacements.items():
        assert installer.count(old) == 1, "Installer fixture target binding drifted"
        installer = installer.replace(old, new)
    fixture_installer = root / "installer.sh"
    fixture_installer.write_text(installer)
    fixture_installer.chmod(0o755)

    environment = dict(os.environ)
    environment.pop("BLACKBOX_URL", None)
    environment["PATH"] = str(binary_dir) + ":" + environment["PATH"]

    def reset():
        subprocess.run(["/usr/bin/ditto", str(source_app), str(destination)], check=True, capture_output=True)
        agent.write_bytes(plistlib.dumps({"Label":"dev.blackbox.quick-recall", "ProgramArguments":[str(executable)],
            "RunAtLoad":True, "EnvironmentVariables":{"BLACKBOX_URL":"https://fixture.example/"}}))
        service_log.unlink(missing_ok=True)

    def run(*arguments, extra=None):
        values = dict(environment)
        if extra:
            values.update(extra)
        return subprocess.run(["/bin/bash", str(fixture_installer), *arguments], env=values, capture_output=True, text=True)

    def snapshot():
        return {str(path.relative_to(root)):hashlib.sha256(path.read_bytes()).hexdigest()
                for path in root.rglob("*") if path.is_file()}

    reset()
    before = snapshot()
    result = run()
    assert result.returncode == 0 and "validated BLACKBOX_URL" in result.stdout, result.stderr
    assert snapshot() == before, "Dry run wrote files"

    result = run("--install")
    assert result.returncode == 0, result.stderr
    state = plistlib.loads(agent.read_bytes())
    assert state["EnvironmentVariables"] == {"BLACKBOX_URL":"https://fixture.example/"}
    assert "KeepAlive" not in state, "Quit should remain effective"
    assert "kickstart" in service_log.read_text(), "Parent startup fix must remain"

print("Installer fixtures passed: no-write dry run, config preservation, and existing startup behavior")
