#!/usr/bin/env python3
"""Exercise the real installer against isolated targets and inert service/Keychain shims."""
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
    key_marker = root / "key-configure-called"

    def script(path, text):
        path.write_text("#!/bin/bash\nset -euo pipefail\n" + text)
        path.chmod(0o755)

    script(binary_dir / "pgrep", "exit 1\n")
    script(binary_dir / "codesign", "exit 0\n")
    script(binary_dir / "launchctl", f'''if [[ "$1" == print ]]; then printf 'pid = {os.getpid()}\\n'; else printf '%s\\n' "$1" >> {shlex.quote(str(service_log))}; fi\n''')
    installed_stub = f'''case "${{1:-}}" in
--check-jev-key) echo present ;;
--configure-jev-key) touch {shlex.quote(str(key_marker))}; [[ "${{FIXTURE_CONFIG_FAIL:-0}}" != 1 ]] ;;
*) exit 2 ;;
esac
'''
    stub_path = root / "installed-cli-stub"
    script(stub_path, installed_stub)
    script(binary_dir / "ditto", f'''/usr/bin/ditto "$1" "$2"
cp {shlex.quote(str(stub_path))} "$2/Contents/MacOS/BlackBoxRecall"
''')

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
    environment.pop("TYPESAFE_API_KEY", None)
    environment["PATH"] = str(binary_dir) + ":" + environment["PATH"]

    def reset(enabled="1"):
        subprocess.run(["/usr/bin/ditto", str(source_app), str(destination)], check=True, capture_output=True)
        agent.write_bytes(plistlib.dumps({"Label":"dev.blackbox.quick-recall", "ProgramArguments":[str(executable)],
            "RunAtLoad":True, "EnvironmentVariables":{"BLACKBOX_URL":"https://fixture.example/", "BLACKBOX_JEV_ENABLED":enabled}}))
        service_log.unlink(missing_ok=True)
        key_marker.unlink(missing_ok=True)

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
    assert result.returncode == 0 and "existing Jev enable flag" in result.stdout, result.stderr
    assert snapshot() == before, "Dry run wrote files"
    result = run("--enable-jev")
    assert result.returncode == 2 and snapshot() == before, "Enable-only dry run must reject without writes"
    result = run("--install", "--enable-jev")
    assert result.returncode == 1 and snapshot() == before, "Missing-key setup must fail before install"

    result = run("--install")
    assert result.returncode == 0, result.stderr
    state = plistlib.loads(agent.read_bytes())
    assert state["EnvironmentVariables"] == {"BLACKBOX_URL":"https://fixture.example/", "BLACKBOX_JEV_ENABLED":"1"}
    assert not key_marker.exists(), "Ordinary update configured a key implicitly"
    assert "KeepAlive" not in state, "Quit should remain effective"
    assert "kickstart" in service_log.read_text(), "Parent startup fix must remain"

    reset("0")
    result = run("--install", "--enable-jev", extra={"TYPESAFE_API_KEY":"fixture-credential"})
    assert result.returncode == 0 and key_marker.exists(), result.stderr
    state = plistlib.loads(agent.read_bytes())
    assert state["EnvironmentVariables"]["BLACKBOX_JEV_ENABLED"] == "1"
    assert b"fixture-credential" not in agent.read_bytes(), "Credential persisted in launch plist"
    assert "fixture-credential" not in result.stdout + result.stderr, "Credential leaked in installer output"

    reset("0")
    old_agent = agent.read_bytes()
    old_binary = executable.read_bytes()
    result = run("--install", "--enable-jev", extra={"TYPESAFE_API_KEY":"fixture-credential", "FIXTURE_CONFIG_FAIL":"1"})
    assert result.returncode != 0 and agent.read_bytes() == old_agent, "Failed setup replaced existing login configuration"
    assert not service_log.exists(), "Failed setup interrupted the running service"
    assert executable.read_bytes() == old_binary, "Failed setup did not restore the previous bundle"

print("Installer fixtures passed: no-write dry run, explicit enable, config preservation, no credential persistence, setup failure, and existing startup behavior")
