#!/usr/bin/env python3
"""Cloud acceptance: one durable synthetic capture, later recall, and real MCP negotiation.

Examples (state belongs outside any Git checkout):
  python3 scripts/cloud/lightsail_verify.py capture --profile default \
    --account-id YOUR_ACCOUNT --state /tmp/blackbox-proof/run.json
  # Add --apply only to the capture command to create one synthetic decision.
  # After redeploying the container, use the same arguments with:
  #   recall --after-redeploy
  # Use `protocols` without --state to initialize MCP and list real server tools.

The state is evidence and an at-most-once submission journal, not a retry queue.
If the POST outcome is uncertain, rerunning capture only searches for its marker;
absence is an error, never permission to resubmit. Do not delete pending evidence
or choose a new state path to work around an uncertain write. No secret is stored.
This verifies deterministic capture/recall, not autonomous AI or SSE longevity.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_BODY = 1024 * 1024
SOURCE = "cloud-acceptance"


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def now():
    return datetime.now(timezone.utc).isoformat()


class Aws:
    def __init__(self, profile, region):
        self.prefix = ["aws", "--profile", profile, "--region", region, "--no-cli-pager"]

    def __call__(self, *args):
        result = subprocess.run(self.prefix + list(args) + ["--output", "json"],
                                capture_output=True, text=True)
        require(result.returncode == 0, "AWS read failed: " + " ".join(args[:2]) + "; inspect privately")
        try:
            return json.loads(result.stdout)
        except ValueError:
            raise VerificationError("AWS read returned invalid JSON") from None


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Http:
    def __init__(self, url, token):
        self.url = url
        self.token = token
        self.opener = build_opener(NoRedirect())

    def request(self, method, path, payload=None, extra_headers=None, rpc_id=None, empty=False):
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/json"}
        headers.update(extra_headers or {})
        data = json.dumps(payload).encode() if payload is not None else None
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.url + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=20) as response:
                require(200 <= response.status < 300, "HTTP request did not succeed")
                response_headers = dict(response.headers.items())
                if empty:
                    return {}, response_headers
                if "text/event-stream" in response.headers.get("Content-Type", ""):
                    require(rpc_id is not None, "Unexpected SSE response for REST request")
                    body = self.read_rpc_sse(response, rpc_id)
                else:
                    raw = response.read(MAX_BODY + 1)
                    require(len(raw) <= MAX_BODY, "Response exceeded the acceptance size limit")
                    body = json.loads(raw)
                require(isinstance(body, dict), "Expected a JSON object")
                return body, response_headers
        except HTTPError as error:
            error.close()
            raise VerificationError("HTTP " + str(error.code) + "; redirects are refused and response bodies are not logged") from None
        except (URLError, TimeoutError, OSError, HTTPException):
            raise VerificationError("HTTP transport failed; a submitted capture may have committed") from None
        except (ValueError, UnicodeError):
            raise VerificationError("Invalid JSON response; a submitted capture may have committed") from None

    @staticmethod
    def read_rpc_sse(response, rpc_id):
        size, data = 0, []
        deadline = time.monotonic() + 30
        while True:
            require(time.monotonic() < deadline, "MCP response did not arrive within the bounded handshake")
            line = response.readline(MAX_BODY + 1)
            size += len(line)
            require(size <= MAX_BODY, "MCP response exceeded the acceptance size limit")
            if not line:
                raise VerificationError("MCP stream ended before the requested response")
            value = line.decode("utf-8").rstrip("\r\n")
            if value.startswith("data:"):
                data.append(value[5:].lstrip(" "))
            elif not value and data:
                message = json.loads("\n".join(data))
                data = []
                if isinstance(message, dict) and message.get("id") == rpc_id:
                    return message


def resolve(aws, args):
    require(args.region == "us-east-2", "This prototype is restricted to us-east-2")
    identity = aws("sts", "get-caller-identity")
    require(identity.get("Account") == args.account_id, "AWS account does not match the explicit target")
    stacks = aws("cloudformation", "describe-stacks", "--stack-name", args.stack_name).get("Stacks", [])
    require(len(stacks) == 1, "Expected exactly one selected stack")
    stack = stacks[0]
    tags = {entry["Key"]: entry["Value"] for entry in stack.get("Tags", [])}
    require(tags.get("Project") == "black-box" and tags.get("Purpose") == "shared-container-prototype",
            "Selected stack is not the Black Box shared container prototype")
    require(stack.get("StackStatus") in ("CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE"),
            "Stack is not in a completed state")
    outputs = {entry["OutputKey"]: entry["OutputValue"] for entry in stack.get("Outputs", [])}
    url = outputs.get("Url", "").rstrip("/")
    parsed = urlsplit(url)
    require(parsed.scheme == "https" and re.fullmatch(r"[a-z0-9-]+(?:\.[a-z0-9-]+)*\.us-east-2\.cs\.amazonlightsail\.com", parsed.netloc or "")
            and not parsed.path and not parsed.query and not parsed.fragment,
            "Stack URL is not the expected Ohio Lightsail HTTPS endpoint")
    secret_arn = outputs.get("ApiSecretArn", "")
    require(secret_arn.startswith("arn:aws:secretsmanager:us-east-2:" + args.account_id + ":secret:"),
            "API secret ARN does not match the selected account and region")
    service_name = outputs.get("ServiceName", "")
    require(re.fullmatch(r"[a-z0-9-]+", service_name), "Invalid container service name")
    services = aws("lightsail", "get-container-services", "--service-name", service_name).get("containerServices", [])
    require(len(services) == 1, "Expected exactly one container service")
    service = services[0]
    require(service.get("containerServiceName") == service_name and service.get("url", "").rstrip("/") == url,
            "Container service differs from the stack output")
    deployment = service.get("currentDeployment", {})
    require(deployment.get("state") == "ACTIVE" and isinstance(deployment.get("version"), int),
            "No active versioned container deployment")
    image = deployment.get("containers", {}).get("blackbox", {}).get("image")
    require(isinstance(image, str) and image.startswith(":"), "Active image is not a registered private Lightsail image")
    # Do not retain the full service response: it includes resolved environment secrets.
    return {"account": args.account_id, "region": args.region, "stack": args.stack_name,
            "stackId": stack["StackId"], "service": service_name, "url": url,
            "apiSecretArn": secret_arn, "deploymentVersion": deployment["version"], "image": image}


def client(aws, target, http_factory):
    response = aws("secretsmanager", "get-secret-value", "--secret-id", target["apiSecretArn"])
    try:
        token = json.loads(response["SecretString"])["token"]
    except (KeyError, ValueError, TypeError):
        raise VerificationError("API secret does not contain the expected token field") from None
    require(isinstance(token, str) and len(token) >= 32 and token.isalnum(), "API token does not meet the generated secret contract")
    return http_factory(target["url"], token)


@contextmanager
def journal(path):
    path = Path(path).expanduser().resolve()
    require(not any((parent / ".git").exists() for parent in [path.parent, *path.parents]),
            "Acceptance state must be outside every Git checkout")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock = path.with_suffix(path.suffix + ".lock")
    fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield path


def save(path, state):
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as handle:
            json.dump(state, handle, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        # Persist the directory entry before allowing the non-idempotent POST.
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load(path, target):
    try:
        state = json.loads(path.read_text())
    except (ValueError, OSError):
        raise VerificationError("Cannot read acceptance journal; preserve it and inspect privately") from None
    require(state.get("formatVersion") == 1, "Unsupported acceptance journal")
    for key in ("account", "region", "stack", "stackId", "service", "url", "apiSecretArn"):
        require(state.get("target", {}).get(key) == target[key], "Acceptance journal targets a different stack or endpoint")
    require(state.get("phase") in ("pending", "captured", "verified"), "Invalid journal phase")
    require(isinstance(state.get("decision"), str) and state["decision"] and state.get("scope", "").startswith("/prototype/cloud-proof/"),
            "Invalid synthetic decision journal")
    return state


def recall(http, state):
    query = urlencode({"scope": state["scope"], "withinHours": 8760, "kinds": "decision", "limit": 100})
    body, _ = http.request("GET", "/api/recall?" + query)
    require(isinstance(body.get("items"), list), "Recall did not return an items list")
    event_id = state.get("eventId")
    matches = [item for item in body["items"] if isinstance(item, dict)
               and (item.get("eventId") == event_id if event_id else item.get("clientSessionId") == state["clientSessionId"])]
    require(len(matches) == 1, "Expected one captured decision in recall; keep journal and investigate, do not resubmit")
    item = matches[0]
    require(item.get("headline") == state["decision"] and item.get("repo") == state["scope"]
            and item.get("clientSessionId") == state["clientSessionId"] and item.get("source") == SOURCE
            and item.get("kind") == "decision" and isinstance(item.get("eventId"), str) and item["eventId"],
            "Recalled decision identity or content differs from the saved intent")
    return item["eventId"]


def proof(args, aws, target, http_factory):
    if args.command == "capture" and not args.apply:
        print(json.dumps({"mode": "dry-run", "action": "capture one synthetic decision", "target": target,
                          "state": str(Path(args.state).expanduser()), "requires": "--apply"}))
        return
    with journal(args.state) as path:
        state = load(path, target) if path.exists() else None
        require(state is not None or args.command == "capture", "No capture journal exists")
        if args.command == "recall" and args.after_redeploy:
            require(target["deploymentVersion"] > state["target"]["deploymentVersion"],
                    "No later active container deployment; persistence across redeployment is not yet proven")
        http = client(aws, target, http_factory)
        if state is None:
            run_id = uuid.uuid4().hex
            state = {"formatVersion": 1, "phase": "pending", "createdAt": now(), "target": target,
                     "runId": run_id, "clientSessionId": "cloud-proof-" + run_id,
                     "scope": "/prototype/cloud-proof/" + run_id,
                     "decision": "Synthetic acceptance: retain cloud memory across container redeployment (" + run_id + ")."}
            save(path, state)
            response, _ = http.request("POST", "/api/decisions", {
                "source": SOURCE, "clientSessionId": state["clientSessionId"], "repo": state["scope"],
                "decision": state["decision"], "rationale": "Deterministic infrastructure acceptance fixture; not a product decision or AI run.",
                "alternatives": [], "confidence": 1.0, "openLoops": []})
            require(isinstance(response.get("eventId"), str) and response["eventId"]
                    and response.get("source") == SOURCE and response.get("clientSessionId") == state["clientSessionId"],
                    "Capture response cannot establish identity; journal remains pending; rerun only performs read-back")
            state.update(phase="captured", eventId=response["eventId"], submittedAt=now())
            save(path, state)
        # Existing pending journals only search; this branch never POSTs again.
        state.update(phase="verified", eventId=recall(http, state), verifiedAt=now(),
                     lastObservedDeploymentVersion=target["deploymentVersion"])
        if args.command == "recall" and args.after_redeploy:
            state["redeploymentVerifiedAt"] = now()
        save(path, state)
        print(json.dumps({"result": "verified", "eventId": state["eventId"], "scope": state["scope"],
                          "capturedDeploymentVersion": state["target"]["deploymentVersion"],
                          "activeDeploymentVersion": target["deploymentVersion"],
                          "afterRedeploy": bool(args.command == "recall" and args.after_redeploy)}))


def rpc_result(message, identifier):
    require(message.get("jsonrpc") == "2.0" and message.get("id") == identifier and "error" not in message
            and isinstance(message.get("result"), dict), "MCP did not return a successful matching JSON-RPC response")
    return message["result"]


def protocols(aws, target, http_factory):
    http = client(aws, target, http_factory)
    headers = {"Accept": "application/json, text/event-stream"}
    response, response_headers = http.request("POST", "/mcp", {
        "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "blackbox-cloud-acceptance", "version": "1.0"}}}, headers, rpc_id=1)
    initialized = rpc_result(response, 1)
    version = initialized.get("protocolVersion")
    require(version in ("2024-11-05", "2025-03-26", "2025-06-18"), "MCP negotiated an unsupported protocol version")
    headers["MCP-Protocol-Version"] = version
    session = next((value for key, value in response_headers.items() if key.lower() == "mcp-session-id"), None)
    if session:
        headers["Mcp-Session-Id"] = session
    http.request("POST", "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"}, headers, empty=True)
    response, _ = http.request("POST", "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, headers, rpc_id=2)
    tools = rpc_result(response, 2).get("tools")
    require(isinstance(tools, list) and tools and all(isinstance(tool, dict) and isinstance(tool.get("name"), str) for tool in tools),
            "MCP tools/list did not return named tools")
    print(json.dumps({"result": "MCP initialized and tools listed", "protocolVersion": version,
                      "toolCount": len(tools), "tools": [tool["name"] for tool in tools],
                      "toolExecutionTested": False, "sseLongevityTested": False}))


def main(argv=None, aws_factory=Aws, http_factory=Http):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("capture", "recall", "protocols"))
    parser.add_argument("--profile", required=True)
    parser.add_argument("--account-id", required=True)
    parser.add_argument("--region", default="us-east-2")
    parser.add_argument("--stack-name", default="blackbox-cloud")
    parser.add_argument("--state", help="Journal outside every Git checkout; required for capture and recall")
    parser.add_argument("--apply", action="store_true", help="Allow the first synthetic decision POST")
    parser.add_argument("--after-redeploy", action="store_true", help="Require a later active deployment during recall")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"\d{12}", args.account_id):
        parser.error("--account-id must be an explicit 12-digit AWS account")
    if args.command != "protocols" and not args.state:
        parser.error("--state is required for capture and recall")
    if args.apply and args.command != "capture":
        parser.error("--apply is only supported with capture")
    if args.after_redeploy and args.command != "recall":
        parser.error("--after-redeploy is only supported with recall")
    try:
        aws = aws_factory(args.profile, args.region)
        target = resolve(aws, args)
        if args.command == "protocols":
            protocols(aws, target, http_factory)
        else:
            proof(args, aws, target, http_factory)
        return 0
    except (VerificationError, OSError, KeyError, TypeError) as error:
        # Only controlled errors may reach output; raw provider/server data can contain credentials.
        print("Acceptance failed: " + (str(error) if isinstance(error, VerificationError) else "Invalid provider data or local journal failure; inspect privately"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
