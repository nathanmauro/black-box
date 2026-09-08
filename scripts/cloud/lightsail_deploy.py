#!/usr/bin/env python3
"""Read-only preflight by default; explicit --apply deploys one shared Lightsail prototype."""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "infra/aws/lightsail.json"
APPROVED_REGION = "us-east-2"
BASE_SECRET_COST = Decimal("1.20")
CA_URL = "https://truststore.pki.rds.amazonaws.com/us-east-2/us-east-2-bundle.pem"
CA_SHA256 = "d46e1bdfda05c8e7644e50930806a19b139a222542bf0348082fb59ece2b5fa5"


class Aws:
    def __init__(self, profile, region):
        self.prefix = ["aws", "--profile", profile, "--region", region, "--no-cli-pager"]

    def __call__(self, *args):
        completed = subprocess.run(self.prefix + list(args) + ["--output", "json"], capture_output=True, text=True)
        if completed.returncode:
            # No command contains plaintext credentials. Never print successful API payloads:
            # Lightsail describe responses can contain resolved container environment secrets.
            raise RuntimeError(completed.stderr.strip() or "AWS command failed")
        if args[:2] == ("cloudformation", "deploy"):
            if completed.stdout.strip():
                print(completed.stdout.strip(), flush=True)
            return {}
        if args[:2] == ("lightsail", "push-container-image"):
            return parse_image_push(completed.stdout, completed.stderr,
                                    service_name=args[args.index("--service-name") + 1],
                                    label=args[args.index("--label") + 1])
        return json.loads(completed.stdout) if completed.stdout.strip() else {}


def parse_image_push(stdout, stderr="", service_name=None, label=None):
    # lightsailctl v1.0.8 prints fmt.Printf success lines, even with --output json:
    # https://github.com/aws/lightsailctl/blob/v1.0.8/internal/cs/pushimage.go
    # AWS CLI inherits the plugin's streams; accept either without logging raw output.
    candidates, digests = set(), set()
    decoder = json.JSONDecoder()
    for output in (stdout, stderr):
        for match in re.finditer(r'^Refer to this image as "(:[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+\.[0-9]+)" in deployments\.[ \t]*$', output, re.MULTILINE):
            candidates.add(match.group(1))
        digests.update(re.findall(r'^Digest: (sha256:[0-9a-f]{64})[ \t]*$', output, re.MULTILINE))
        # Retain compatibility with structured wrappers, but return only validated metadata.
        for offset, character in enumerate(output):
            if character != "{":
                continue
            try:
                payload, _ = decoder.raw_decode(output[offset:])
            except ValueError:
                continue
            registered = payload.get("containerImage") if isinstance(payload, dict) else None
            if not isinstance(registered, dict):
                continue
            image = registered.get("image")
            if isinstance(image, str) and re.fullmatch(r":[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+\.[0-9]+", image):
                candidates.add(image)
            digest = registered.get("digest")
            if isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
                digests.add(digest)
    if len(candidates) != 1 or len(digests) > 1:
        raise RuntimeError("Image push did not identify exactly one registered image; upload may have succeeded. "
                           "Inspect get-container-images; do not guess a version or blindly repeat the upload")
    image = next(iter(candidates))
    parts = image[1:].split(".")
    if (service_name is not None and parts[0] != service_name) or (label is not None and parts[1] != label):
        raise RuntimeError("Registered image does not match the requested service and label; refusing deployment")
    registered = {"image": image}
    if digests:
        registered["digest"] = next(iter(digests))
    return {"containerImage": registered}


def pages(aws, operation, key):
    records, token = [], None
    while True:
        response = aws("lightsail", operation, *(["--page-token", token] if token else []))
        records.extend(response.get(key, []))
        token = response.get("nextPageToken")
        if not token:
            return records


def stack_state(aws, name):
    try:
        stack = aws("cloudformation", "describe-stacks", "--stack-name", name)["Stacks"][0]
    except RuntimeError as error:
        if "does not exist" in str(error):
            return None
        raise
    tags = {item["Key"]: item["Value"] for item in stack.get("Tags", [])}
    if tags.get("Project") != "black-box" or tags.get("Purpose") != "shared-container-prototype":
        raise RuntimeError("Selected existing stack is unrelated; refusing to modify it")
    if stack["StackStatus"].endswith("IN_PROGRESS"):
        raise RuntimeError("Selected stack already has an operation in progress; wait and inspect it")
    return stack


def preflight(aws, args):
    if args.region != APPROVED_REGION:
        raise RuntimeError("This prototype is authorized only in us-east-2")
    identity = aws("sts", "get-caller-identity")
    if identity["Account"] != args.account_id:
        raise RuntimeError("Profile resolves to a different AWS account; refusing to continue")
    powers = aws("lightsail", "get-container-service-powers")["powers"]
    power = next((item for item in powers if item.get("name", "").lower() == "small" and item.get("isActive")), None)
    if power is None or Decimal(str(power["price"])) != Decimal("15") or power["ramSizeInGb"] != 1:
        raise RuntimeError("Expected active USD15 / 1GB small container plan is unavailable")
    bundles = pages(aws, "get-relational-database-bundles", "bundles")
    eligible = [item for item in bundles if item.get("isActive") and item.get("isEncrypted")
                and Decimal(str(item["price"])) == Decimal("15") and item["ramSizeInGb"] == 1]
    if not eligible:
        raise RuntimeError("No active encrypted USD15 / 1GB database bundle; do not fall back to unencrypted")
    total = Decimal(str(power["price"])) + Decimal("15") + BASE_SECRET_COST
    if total > Decimal("50"):
        raise RuntimeError("Base plan exceeds the USD50 infrastructure ceiling")
    blueprints = pages(aws, "get-relational-database-blueprints", "blueprints")
    supported = [item for item in blueprints if item.get("isEngineDefault") is not None
                 and item.get("engine", "").lower() in ("postgres", "postgresql")
                 and not item.get("isInactive", False)]
    # API blueprints do not expose isActive; includeInactive defaults false.
    if not supported:
        raise RuntimeError("No active PostgreSQL blueprint returned")
    supported.sort(key=lambda item: tuple(int(part) for part in re.findall(r"\d+", item.get("engineVersion", "0"))), reverse=True)
    stack = stack_state(aws, args.stack_name)
    previous = {item["ParameterKey"]: item["ParameterValue"] for item in stack.get("Parameters", [])} if stack else {}
    if previous and previous.get("ServiceName") != args.service_name:
        raise RuntimeError("Changing the established service name can replace resources; refusing")
    bundle_id = previous.get("DatabaseBundle", eligible[0]["bundleId"])
    if bundle_id not in {item["bundleId"] for item in eligible}:
        raise RuntimeError("Existing database bundle no longer matches the validated encrypted USD15 plan")
    blueprint_id = previous.get("DatabaseBlueprint", supported[0]["blueprintId"])
    aws("cloudformation", "validate-template", "--template-body", "file://" + str(TEMPLATE))
    return {"account": identity["Account"], "region": args.region, "stack": args.stack_name,
            "service": args.service_name, "databaseBundle": bundle_id, "databaseBlueprint": blueprint_id,
            "monthlyBaseUsd": str(total), "stackExists": bool(stack), "previousParameters": previous}


def inspect_image(args):
    result = subprocess.run(["docker", "image", "inspect", args.image], check=True, capture_output=True, text=True)
    image = json.loads(result.stdout)[0]
    labels = image.get("Config", {}).get("Labels", {})
    if image["Id"] != args.image_id:
        raise RuntimeError("Local image ID differs from the reviewed image ID")
    if image.get("Architecture") != "amd64" or image.get("Os") != "linux":
        raise RuntimeError("Lightsail release must be built as linux/amd64")
    if labels.get("org.opencontainers.image.revision") != args.source_revision or args.source_revision == "unverified":
        raise RuntimeError("Image source revision differs from the verified integrated source")
    if labels.get("io.blackbox.cloud-contract") != "postgres-auth-v1":
        raise RuntimeError("Image lacks the explicit PostgreSQL/auth release contract")
    if not re.fullmatch(r"[0-9a-f]{64}", labels.get("io.blackbox.jar-sha256", "")):
        raise RuntimeError("Image lacks a concrete packaged JAR checksum")
    if image.get("Config", {}).get("User") != "10001:10001":
        raise RuntimeError("Image must run as the dedicated unprivileged UID/GID")
    if shutil.which("lightsailctl") is None:
        raise RuntimeError("Install the official lightsailctl plugin before an image push")
    return {"imageId": image["Id"], "sourceRevision": args.source_revision, "jarSha256": labels["io.blackbox.jar-sha256"]}


def guard_changes(changes, existing):
    unsafe = []
    for change in changes:
        resource = change.get("ResourceChange", {})
        if resource.get("Action") == "Remove" or (existing and resource.get("Action") == "Modify" and resource.get("Replacement") in ("True", "Conditional")):
            unsafe.append(resource.get("LogicalResourceId", "unknown"))
    if unsafe:
        raise RuntimeError("Change set removes or may replace existing resources; refusing automatic execution: " + ", ".join(unsafe))


def cfn_deploy(aws, args, parameters):
    existing = stack_state(aws, args.stack_name) is not None
    change_name = "blackbox-" + uuid.uuid4().hex
    result = aws("cloudformation", "create-change-set", "--stack-name", args.stack_name,
                 "--change-set-name", change_name, "--change-set-type", "UPDATE" if existing else "CREATE",
                 "--template-body", "file://" + str(TEMPLATE),
                 "--tags", json.dumps([{"Key": "Project", "Value": "black-box"}, {"Key": "Purpose", "Value": "shared-container-prototype"}]),
                 "--parameters", json.dumps([{"ParameterKey": key, "ParameterValue": value} for key, value in parameters.items()]))
    identifier = result["Id"]
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        change_set = aws("cloudformation", "describe-change-set", "--change-set-name", identifier)
        if change_set["Status"] == "FAILED":
            reason = change_set.get("StatusReason", "")
            if "didn't contain changes" in reason or "No updates are to be performed" in reason:
                aws("cloudformation", "delete-change-set", "--change-set-name", identifier)
                print("No CloudFormation changes.", flush=True)
                return
            raise RuntimeError("Change set failed before execution: " + reason)
        if change_set["Status"] == "CREATE_COMPLETE":
            break
        time.sleep(3)
    else:
        raise RuntimeError("Change set was not ready; no resource execution requested")
    changes = list(change_set.get("Changes", []))
    token = change_set.get("NextToken")
    while token:
        extra = aws("cloudformation", "describe-change-set", "--change-set-name", identifier, "--next-token", token)
        changes.extend(extra.get("Changes", []))
        token = extra.get("NextToken")
    guard_changes(changes, existing)
    print(json.dumps({"reviewedChangeSet": identifier, "resources": [
        {key: item.get("ResourceChange", {}).get(key) for key in ("LogicalResourceId", "Action", "Replacement")} for item in changes]}), flush=True)
    aws("cloudformation", "execute-change-set", "--change-set-name", identifier)
    aws("cloudformation", "wait", "stack-update-complete" if existing else "stack-create-complete", "--stack-name", args.stack_name)


def wait_database(aws, database_name, expected_bundle, timeout=3600):
    # GetRelationalDatabase has no isEncrypted field. Its deployed bundle ID plus
    # the live bundle catalog is the available encryption evidence, not an
    # independent per-database encryption attestation.
    bundles = pages(aws, "get-relational-database-bundles", "bundles")
    matches = [item for item in bundles if item.get("bundleId") == expected_bundle]
    if len(matches) != 1 or matches[0].get("isEncrypted") is not True:
        raise RuntimeError("Cannot establish encryption from the exact selected database bundle catalog record")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        db = aws("lightsail", "get-relational-database", "--relational-database-name", database_name)["relationalDatabase"]
        if db.get("relationalDatabaseBundleId") != expected_bundle:
            raise RuntimeError("Deployed database bundle does not match the selected encrypted catalog bundle")
        if db.get("state") == "available" and db.get("masterEndpoint", {}).get("address"):
            print(json.dumps({"databaseEncryptionEvidence": "deployed bundle ID matched live encrypted bundle catalog",
                              "databaseBundle": expected_bundle, "catalogIsEncrypted": True}), flush=True)
            return db
        if db.get("state") in ("failed", "deleting", "inaccessible-encryption-credentials"):
            raise RuntimeError("Database entered a failure state: " + db["state"])
        time.sleep(15)
    raise RuntimeError("Database did not become available; inspect the selected stack, do not create a duplicate")


def wait_container(aws, service_name, expected_image, timeout=900):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        service = aws("lightsail", "get-container-services", "--service-name", service_name)["containerServices"][0]
        deployment = service.get("currentDeployment", {})
        image = deployment.get("containers", {}).get("blackbox", {}).get("image")
        if deployment.get("state") == "ACTIVE" and image == expected_image:
            return service["url"].rstrip("/")
        next_deployment = service.get("nextDeployment", {})
        if next_deployment.get("state") == "FAILED":
            raise RuntimeError("Container deployment failed; inspect logs privately (they may contain secrets)")
        time.sleep(10)
    raise RuntimeError("Expected image did not become active; inspect deployment state before retrying")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def http_status(url, token=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    try:
        with build_opener(NoRedirect()).open(Request(url, headers=headers), timeout=20) as response:
            return response.status
    except HTTPError as error:
        return error.code


def verify_public_auth(aws, stack, url):
    if not url.startswith("https://") or not url.endswith(".cs.amazonlightsail.com"):
        raise RuntimeError("Unexpected service URL; refusing to send credentials")
    if http_status(url + "/actuator/health") != 200:
        raise RuntimeError("Anonymous health check did not pass")
    for path in ("/api/status", "/api/recall", "/mcp"):
        if http_status(url + path) != 401:
            raise RuntimeError("Authentication boundary failed at " + path)
    outputs = {item["OutputKey"]: item["OutputValue"] for item in stack["Outputs"]}
    response = aws("secretsmanager", "get-secret-value", "--secret-id", outputs["ApiSecretArn"])
    token = json.loads(response["SecretString"])["token"]
    if http_status(url + "/api/status", token) != 200:
        raise RuntimeError("Authenticated status request failed")
    return {"health": "passed", "unauthenticatedApiAndMcp": "rejected", "authenticatedStatus": "passed"}


def verify_database_tls(host, port):
    # Verify the actual PostgreSQL TLS handshake without retrieving/sending the DB password.
    with urlopen(CA_URL, timeout=20) as response:
        ca = response.read()
    if hashlib.sha256(ca).hexdigest() != CA_SHA256:
        raise RuntimeError("AWS CA bundle changed; review and update the pinned image and verifier")
    context = ssl.create_default_context(cadata=ca.decode())
    with socket.create_connection((host, port), timeout=20) as raw:
        raw.sendall(struct.pack("!II", 8, 80877103))
        if raw.recv(1) != b"S":
            raise RuntimeError("PostgreSQL endpoint refused TLS")
        with context.wrap_socket(raw, server_hostname=host) as protected:
            return {"databaseTls": protected.version(), "certificateHostnameVerified": True}


def receive_exact(connection, length):
    result = b""
    while len(result) < length:
        part = connection.recv(length - len(result))
        if not part:
            raise RuntimeError("PostgreSQL closed during TLS enforcement probe")
        result += part
    return result


def verify_database_requires_tls(host, port):
    # A PostgreSQL StartupMessage contains only the known application username/database.
    # Never answer an authentication challenge and never retrieve or send a password.
    parameters = b"user\x00blackbox\x00database\x00blackbox\x00application_name\x00blackbox-tls-probe\x00\x00"
    startup = struct.pack("!I", 196608) + parameters
    with socket.create_connection((host, port), timeout=20) as connection:
        connection.sendall(struct.pack("!I", len(startup) + 4) + startup)
        header = receive_exact(connection, 5)
        length = struct.unpack("!I", header[1:])[0]
        if length < 4 or length > 65536:
            raise RuntimeError("Invalid PostgreSQL response during TLS enforcement probe")
        payload = receive_exact(connection, length - 4)
        # RDS/Lightsail refuses plaintext with an explicit pg_hba/no-encryption error.
        message = payload.decode("utf-8", errors="replace").lower()
        return header[:1] == b"E" and any(marker in message for marker in ("no encryption", "ssl off", "ssl connection is required", "ssl required"))


def require_database_tls(aws, database_name, host, port, newly_created):
    tls = verify_database_tls(host, port)
    if verify_database_requires_tls(host, port):
        return dict(tls, plaintextStartupRejected=True)
    if not newly_created:
        raise RuntimeError("Existing database accepts plaintext startup; do not deploy until effective rds.force_ssl is verified")
    print("TLS enforcement is pending. Rebooting only the newly created prototype database once.", flush=True)
    aws("lightsail", "reboot-relational-database", "--relational-database-name", database_name)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        time.sleep(10)
        try:
            tls = verify_database_tls(host, port)
            if verify_database_requires_tls(host, port):
                return dict(tls, plaintextStartupRejected=True)
        except (OSError, RuntimeError):
            pass
    raise RuntimeError("Database did not enforce TLS after its initial reboot; application image was not deployed")


def apply(aws, args, facts):
    previous = facts["previousParameters"]
    parameters = {"ServiceName": args.service_name, "DatabaseBundle": facts["databaseBundle"],
                  "DatabaseBlueprint": facts["databaseBlueprint"], "DatabasePort": "5432",
                  "DatabaseHost": previous.get("DatabaseHost", ""), "ContainerImage": previous.get("ContainerImage", "")}
    # A fresh stack has no application deployment until the reviewed image and database are ready.
    cfn_deploy(aws, args, parameters)
    database_name = args.service_name + "-db"
    database = wait_database(aws, database_name, facts["databaseBundle"])
    endpoint = database["masterEndpoint"]
    parameters["DatabaseHost"] = endpoint["address"]
    parameters["DatabasePort"] = str(endpoint["port"])
    tls = require_database_tls(aws, database_name, endpoint["address"], endpoint["port"], not facts["stackExists"])
    print(json.dumps(tls), flush=True)
    if args.prepare_only:
        print("Resources prepared; no new application image deployed. Complete integrated image verification, then run --apply with its immutable identity.")
        return
    if args.rollback_image:
        registered = aws("lightsail", "get-container-images", "--service-name", args.service_name)["containerImages"]
        if args.rollback_image not in {item["image"] for item in registered}:
            raise RuntimeError("Rollback image is not registered to this service")
        image_name = args.rollback_image
    else:
        print("Pushing the reviewed image to the private Lightsail image store...", flush=True)
        label = "r" + args.image_id.removeprefix("sha256:")[:16]
        image_name = aws("lightsail", "push-container-image", "--service-name", args.service_name,
                         "--label", label, "--image", args.image_id)["containerImage"]["image"]
        if not re.fullmatch(r":" + re.escape(args.service_name) + r"\.[a-zA-Z0-9-]+\.[0-9]+", image_name):
            raise RuntimeError("Push response did not identify an immutable private image version")
    parameters["ContainerImage"] = image_name
    cfn_deploy(aws, args, parameters)
    url = wait_container(aws, args.service_name, image_name)
    stack = stack_state(aws, args.stack_name)
    evidence = verify_public_auth(aws, stack, url)
    print(json.dumps({"url": url, "image": image_name, "monthlyBaseUsd": facts["monthlyBaseUsd"], "verification": evidence,
                     "remaining": "Browser login, authenticated capture/recall, SSE/MCP sustained connection, restart persistence"}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--region", default=APPROVED_REGION)
    parser.add_argument("--account-id", required=True, help="Explicit expected 12-digit account; checked against profile identity")
    parser.add_argument("--stack-name", default="blackbox-cloud")
    parser.add_argument("--service-name", default="blackbox-cloud")
    parser.add_argument("--prepare-only", action="store_true", help="Only create/verify DB, secrets, and initially empty container service")
    parser.add_argument("--image", help="Locally built image from integrated PostgreSQL/auth source")
    parser.add_argument("--image-id", help="Exact reviewed docker image ID (sha256:...)")
    parser.add_argument("--source-revision", help="Exact reviewed source revision label")
    parser.add_argument("--rollback-image", help="Existing immutable :service.label.version image; schema compatibility must already be checked")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{12}", args.account_id):
        parser.error("account-id must be 12 digits")
    if not re.fullmatch(r"[a-z][a-z0-9-]{2,35}", args.service_name) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,127}", args.stack_name):
        parser.error("Invalid stack or service name")
    if args.prepare_only and (args.image or args.rollback_image):
        parser.error("prepare-only cannot select an image")
    if args.rollback_image and args.image:
        parser.error("Choose a new image or an existing rollback image, not both")
    if not args.prepare_only and not args.rollback_image and not all((args.image, args.image_id, args.source_revision)):
        parser.error("Require --prepare-only, --rollback-image, or --image with --image-id and --source-revision")
    if args.rollback_image and not re.fullmatch(r":" + re.escape(args.service_name) + r"\.[a-zA-Z0-9-]+\.[0-9]+", args.rollback_image):
        parser.error("Rollback requires an exact private image version; latest is not accepted")
    image_evidence = inspect_image(args) if args.image else {}
    aws = Aws(args.profile, args.region)
    facts = preflight(aws, args)
    print(json.dumps({key: value for key, value in facts.items() if key != "previousParameters"} |
                     {"mode": "APPLY" if args.apply else "DRY RUN", "image": image_evidence}, indent=2), flush=True)
    if args.apply:
        apply(aws, args, facts)
    else:
        print("Read-only preflight passed. No cloud resources, images, deployments, or secrets were changed.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
        sys.exit(str(error))
