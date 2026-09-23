#!/usr/bin/env python3
"""User-local launchd lifecycle. Tunnel key in Keychain; local credential in owner-only runtime file; neither in argv."""
import argparse
import getpass
import hashlib
import io
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import platform
import plistlib
import re
import secrets
import shutil
import shlex
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path.home() / ".local/share/blackbox-chatgpt"
LABEL = "com.nathan.blackbox-chatgpt"
KEYCHAIN_SERVICE = "blackbox-chatgpt-mcp"
CONFIG = ROOT / "config.json"


def keychain_get(account):
    result = subprocess.run(["/usr/bin/security", "find-generic-password", "-s", KEYCHAIN_SERVICE,
                             "-a", account, "-w"], capture_output=True, timeout=15)
    if result.returncode:
        raise RuntimeError("Keychain credential unavailable: " + account)
    return result.stdout.decode().strip()


def keychain_put(account, secret):
    # Interactive stdin avoids putting credentials in process argv or shell history.
    args = ["add-generic-password", "-U", "-s", KEYCHAIN_SERVICE, "-a", account,
            "-w", secret, "-T", "/usr/bin/security"]
    result = subprocess.run(["/usr/bin/security", "-i"], input=shlex.join(args) + "\n",
                            text=True, capture_output=True, timeout=30)
    if result.returncode or keychain_get(account) != secret:
        raise RuntimeError("Keychain save failed; complete the macOS access prompt and retry")


def gateway_token():
    path = ROOT / "gateway-token"
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise RuntimeError("Gateway credential must be an owner-only regular file")
    value = path.read_text().strip()
    if len(value) < 32:
        raise RuntimeError("Invalid gateway credential")
    return value


def load_config():
    return json.loads(CONFIG.read_text())


def save_config(config):
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    ROOT.chmod(0o700)
    temporary = ROOT / "config.json.tmp"
    temporary.write_text(json.dumps(config, indent=2) + "\n")
    temporary.chmod(0o600)
    temporary.replace(CONFIG)


def plist_path(component):
    return Path.home() / "Library/LaunchAgents" / (LABEL + "." + component + ".plist")


def target(component):
    return "gui/%d/%s.%s" % (os.getuid(), LABEL, component)


def launchctl(*args):
    return subprocess.run(["/bin/launchctl", *args], capture_output=True, text=True)


def install_plist(component):
    value = {"Label": LABEL + "." + component,
             "ProgramArguments": [str(ROOT / "venv/bin/python"), str(ROOT / "app/manage.py"),
                                  "supervise", component],
             "WorkingDirectory": str(ROOT), "RunAtLoad": True, "KeepAlive": True,
             "ThrottleInterval": 15, "ExitTimeOut": 15, "ProcessType": "Background",
             "StandardOutPath": "/dev/null", "StandardErrorPath": "/dev/null",
             "EnvironmentVariables": {"PYTHONUNBUFFERED": "1"}}
    p = plist_path(component)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(plistlib.dumps(value))
    p.chmod(0o600)


def start(component):
    if not plist_path(component).exists():
        raise RuntimeError(component + " is not configured")
    if launchctl("print", target(component)).returncode:
        result = launchctl("bootstrap", "gui/%d" % os.getuid(), str(plist_path(component)))
        if result.returncode:
            raise RuntimeError("launchd bootstrap failed for " + component)
    else:
        if launchctl("kickstart", target(component)).returncode:
            raise RuntimeError("launchd kickstart failed for " + component)
    if component == "gateway":
        for _ in range(100):
            if probe(load_config()["port"], "/healthz"):
                return
            time.sleep(.1)
        raise RuntimeError("Gateway did not become healthy; inspect status and supervisor log")


def stop(component):
    launchctl("bootout", target(component))
    # bootout can return before the service finishes leaving the domain.
    for _ in range(100):
        if launchctl("print", target(component)).returncode:
            return
        time.sleep(.2)
    raise RuntimeError("launchd has not finished stopping " + component + "; retry status before starting")


def probe(port, path):
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d%s" % (port, path), timeout=4) as r:
            return r.status == 200
    except (urllib.error.URLError, TimeoutError):
        return False


def status():
    config = load_config()
    out = {"local_mcp_url": "http://127.0.0.1:%d/mcp" % config["port"],
           "tunnel_id": config.get("tunnel_id"), "authentication": "Secure MCP Tunnel workspace access; local Bearer from owner-only runtime file"}
    for component, port in (("gateway", config["port"]), ("tunnel", config["tunnel_health_port"])):
        result = launchctl("print", target(component))
        pid = re.search(r"\bpid = (\d+)", result.stdout)
        out[component] = {"loaded": result.returncode == 0, "pid": int(pid[1]) if pid else None,
                          "healthy": probe(port, "/healthz"), "ready": probe(port, "/readyz")}
    print(json.dumps(out, indent=2))


def install_tunnel_binary():
    request = urllib.request.Request("https://api.github.com/repos/openai/tunnel-client/releases/latest",
                                     headers={"User-Agent": "blackbox-chatgpt-setup"})
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    arch = "arm64" if platform.machine() == "arm64" else "amd64"
    name = "tunnel-client-%s-darwin-%s.zip" % (release["tag_name"], arch)
    asset = next(a for a in release["assets"] if a["name"] == name)
    with urllib.request.urlopen(asset["browser_download_url"], timeout=60) as response:
        data = response.read()
    if asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest():
        raise RuntimeError("Official tunnel-client digest verification failed")
    archive = zipfile.ZipFile(io.BytesIO(data))
    binary = archive.read("tunnel-client")
    (ROOT / "bin").mkdir(exist_ok=True)
    tmp = ROOT / "bin/tunnel-client.new"
    tmp.write_bytes(binary)
    tmp.chmod(0o700)
    tmp.replace(ROOT / "bin/tunnel-client")
    print("Installed digest-verified official tunnel-client " + release["tag_name"])


def install():
    if sys.platform != "darwin":
        raise RuntimeError("launchd installation requires macOS")
    source = Path(__file__).resolve().parent
    if source == ROOT / "app":
        raise RuntimeError("Run install/update from the repository script")
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    config = load_config() if CONFIG.exists() else {"upstream": "http://127.0.0.1:8766", "port": 8767,
                                                      "tunnel_health_port": 8768}
    save_config(config)
    uv = shutil.which("uv")
    if not uv:
        raise RuntimeError("Install uv from its official distribution first")
    if not (ROOT / "venv/bin/python").exists():
        subprocess.run([uv, "venv", "--python", "3.12", str(ROOT / "venv")], check=True)
    stop("tunnel")
    stop("gateway")
    subprocess.run([uv, "pip", "sync", "--python", str(ROOT / "venv/bin/python"),
                    str(source / "requirements.lock")], check=True)
    (ROOT / "app").mkdir(exist_ok=True)
    for name in ("gateway.py", "manage.py", "smoke.py", "requirements.lock"):
        shutil.copy2(source / name, ROOT / "app" / name)
    credential = ROOT / "gateway-token"
    if not credential.exists():
        fd = os.open(credential, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as output:
            output.write(secrets.token_urlsafe(48) + "\n")
    gateway_token()
    if not (ROOT / "bin/tunnel-client").exists():
        install_tunnel_binary()
    install_plist("gateway")
    start("gateway")
    if config.get("tunnel_id"):
        install_plist("tunnel")
        start("tunnel")
    print("Installed local runtime. Run status; tunnel requires a real tunnel ID and runtime key.")


def configure_tunnel(tunnel_id):
    if not re.fullmatch(r"tunnel_[a-zA-Z0-9_-]{8,128}", tunnel_id):
        raise RuntimeError("Use the actual tunnel ID from Platform settings")
    config = load_config()
    config["tunnel_id"] = tunnel_id
    save_config(config)
    install_plist("tunnel")
    print("Tunnel configured. Store its runtime key with set-secret tunnel, then start.")


def tunnel_command(config, command="run"):
    return [str(ROOT / "bin/tunnel-client"), command, "--control-plane.tunnel-id", config["tunnel_id"],
            "--control-plane.api-key", "env:CONTROL_PLANE_API_KEY",
            "--mcp.server-url", "http://127.0.0.1:%d/mcp" % config["port"],
            "--mcp.extra-headers", "Authorization: env:BLACKBOX_GATEWAY_AUTH",
            "--mcp.discovery-extra-headers", "Authorization: env:BLACKBOX_GATEWAY_AUTH",
            "--health.listen-addr", ("127.0.0.1:0" if command == "doctor"
                                     else "127.0.0.1:%d" % config["tunnel_health_port"]),
            "--log.format", "json", "--log.level", "warn"]


def runtime_env(component):
    # Inherit no unrelated API keys, developer config, or tracing flags into the service.
    env = {k: os.environ[k] for k in ("HOME", "PATH", "TMPDIR", "LANG") if k in os.environ}
    env["BLACKBOX_GATEWAY_AUTH"] = "Bearer " + gateway_token()
    if component == "tunnel":
        env["CONTROL_PLANE_API_KEY"] = keychain_get("tunnel")
    return env


def supervise(component):
    os.umask(0o077)
    (ROOT / "logs").mkdir(exist_ok=True)
    handler = RotatingFileHandler(ROOT / "logs" / (component + ".log"), maxBytes=1024*1024, backupCount=3)
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    logger = logging.getLogger("supervisor")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    try:
        config = load_config()
        env = runtime_env(component)
        command = ([sys.executable, str(ROOT / "app/manage.py"), "serve"] if component == "gateway"
                   else tunnel_command(config))
        # Remain in launchd's process group so supervisor crashes also reap the child.
        child = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        def terminate(signum, frame):
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
            raise SystemExit(0)
        signal.signal(signal.SIGTERM, terminate)
        signal.signal(signal.SIGINT, terminate)
        def drain():
            # Never persist child output: even third-party error messages could contain input/secrets.
            for _ in iter(lambda: child.stdout.read(4096), b""):
                pass
        threading.Thread(target=drain, daemon=True).start()
        logger.info("%s started pid=%d", component, child.pid)
        failures = 0
        port = config["port"] if component == "gateway" else config["tunnel_health_port"]
        while child.poll() is None:
            time.sleep(10)
            failures = 0 if probe(port, "/healthz") else failures + 1
            if failures >= 6:
                logger.warning("health probe failed six times; restarting via launchd")
                child.kill()
                child.wait(timeout=10)
        logger.warning("%s exited status=%d; launchd will retry", component, child.returncode)
        return 1
    except (RuntimeError, KeyError, OSError, subprocess.TimeoutExpired):
        logger.error("startup failed; check configuration and Keychain using status/set-secret")
        return 1


def serve():
    import uvicorn
    from gateway import BlackBox, create_app
    config = load_config()
    backend = BlackBox(config["upstream"], ROOT / "receipts.sqlite3")
    token = os.environ["BLACKBOX_GATEWAY_AUTH"].removeprefix("Bearer ")
    uvicorn.run(create_app(backend, token), host="127.0.0.1", port=config["port"],
                access_log=False, log_level="critical", proxy_headers=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("install", "update", "status", "doctor", "serve", "uninstall", "update-tunnel"):
        sub.add_parser(command)
    for command in ("start", "stop", "restart"):
        sub.add_parser(command).add_argument("component", choices=["gateway", "tunnel", "all"], default="all", nargs="?")
    sub.add_parser("configure-tunnel").add_argument("tunnel_id")
    sub.add_parser("set-secret").add_argument("account", choices=["tunnel"])
    sub.add_parser("supervise").add_argument("component", choices=["gateway", "tunnel"])
    args = parser.parse_args()
    if args.command in ("install", "update"):
        install()
    elif args.command == "configure-tunnel":
        configure_tunnel(args.tunnel_id)
    elif args.command == "set-secret":
        value = getpass.getpass("Tunnel runtime API key (hidden; stored only in Keychain): ")
        if len(value) < 32 or any(c.isspace() for c in value):
            raise RuntimeError("Expected a valid runtime API key")
        keychain_put(args.account, value)
        print("Saved to Keychain; no secret written to configuration.")
    elif args.command == "status":
        status()
    elif args.command == "doctor":
        config = load_config()
        if not config.get("tunnel_id"):
            raise RuntimeError("No tunnel ID configured; finish Platform tunnel creation first")
        result = subprocess.run(tunnel_command(config, "doctor"), env=runtime_env("tunnel"),
                                capture_output=True, timeout=60)
        print("Tunnel doctor: " + ("passed" if result.returncode == 0 else
                                   "failed; check runtime-key permissions, tunnel association and local readiness"))
        return result.returncode
    elif args.command == "serve":
        serve()
    elif args.command == "supervise":
        return supervise(args.component)
    elif args.command == "update-tunnel":
        stop("tunnel")
        install_tunnel_binary()
        if load_config().get("tunnel_id"):
            start("tunnel")
    elif args.command == "uninstall":
        for component in ("tunnel", "gateway"):
            stop(component)
            plist_path(component).unlink(missing_ok=True)
        print("Services removed. Runtime, receipts and Keychain retained for safe recovery. "
              "Disconnect the ChatGPT app and retire the Platform tunnel separately.")
    else:
        components = ["gateway", "tunnel"] if args.component == "all" else [args.component]
        if args.command in ("stop", "restart"):
            for component in reversed(components):
                stop(component)
        if args.command in ("start", "restart"):
            for component in components:
                if plist_path(component).exists():
                    start(component)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, KeyError, OSError, subprocess.SubprocessError) as error:
        print("Integration command failed: " + (str(error) if isinstance(error, RuntimeError)
                                                else type(error).__name__), file=sys.stderr)
        sys.exit(1)
