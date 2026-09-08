#!/usr/bin/env python3
"""Build an immutable, non-root release image from the reviewed PostgreSQL/auth JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_ENTRIES = (
    "BOOT-INF/classes/application-postgres.yml",
    "BOOT-INF/classes/dev/nathan/sbaagentic/platform/internal/adapter/in/web/security/WebSecurityConfiguration.class",
    "BOOT-INF/classes/dev/nathan/sbaagentic/platform/internal/adapter/in/web/security/AuthSettings.class",
)


def inspect_jar(path):
    if not path.is_file() or not zipfile.is_zipfile(path):
        raise RuntimeError("Expected an existing packaged Spring Boot JAR")
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        missing = [entry for entry in REQUIRED_ENTRIES if entry not in names]
        if missing:
            raise RuntimeError("JAR is missing integrated PostgreSQL/auth classes: " + ", ".join(missing))
        if not any(name.startswith("BOOT-INF/lib/postgresql-") for name in names):
            raise RuntimeError("JAR does not contain the PostgreSQL JDBC driver")
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--source-revision", required=True, help="Reviewed integrated source revision; include a dirty-build identifier when appropriate")
    parser.add_argument("--tag", help="Optional local image tag; default derives from the JAR hash")
    parser.add_argument("--apply", action="store_true", help="Build the local image; never creates cloud resources")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{5,100}", args.source_revision) or args.source_revision == "unverified":
        parser.error("source-revision must identify the reviewed integrated source")
    jar = args.jar.resolve()
    try:
        relative_jar = jar.relative_to(ROOT.resolve())
    except ValueError:
        parser.error("Copy the reviewed JAR beneath this checkout's target/ directory before building")
    if relative_jar.parts[0] != "target" or not jar.name.startswith("sba-agentic-") or jar.suffix != ".jar":
        parser.error("JAR must match target/sba-agentic-*.jar to stay within the minimal Docker context")
    checksum = inspect_jar(jar)
    tag = args.tag or "blackbox:cloud-" + checksum[:16]
    plan = {"jarSha256": checksum, "sourceRevision": args.source_revision, "tag": tag, "platform": "linux/amd64",
            "mode": "LOCAL BUILD" if args.apply else "DRY RUN", "note": "Artifact presence is not a substitute for the parent's HTTP/auth/PG verification."}
    print(json.dumps(plan, indent=2), flush=True)
    if not args.apply:
        return
    subprocess.run(["docker", "build", "--file", str(ROOT / "Dockerfile.cloud"), "--platform", "linux/amd64", "--pull", "--tag", tag,
                    "--build-arg", "JAR_FILE=" + str(relative_jar), "--build-arg", "SOURCE_REVISION=" + args.source_revision,
                    "--build-arg", "JAR_SHA256=" + checksum, str(ROOT)], check=True)
    result = subprocess.run(["docker", "image", "inspect", tag], check=True, capture_output=True, text=True)
    image = json.loads(result.stdout)[0]
    print(json.dumps({"image": tag, "imageId": image["Id"], "sourceRevision": args.source_revision,
                      "jarSha256": checksum, "user": image["Config"]["User"], "architecture": image["Architecture"]}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as error:
        sys.exit(str(error))
