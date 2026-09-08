#!/usr/bin/env python3
"""Offline safety contracts for the shared Lightsail deployment."""
import contextlib
import importlib.util
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import struct
import threading
import tempfile
import zipfile
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("lightsail_deploy", HERE / "lightsail_deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

build_spec = importlib.util.spec_from_file_location("lightsail_build", HERE / "lightsail_build.py")
build = importlib.util.module_from_spec(build_spec)
build_spec.loader.exec_module(build)


class FakeAws:
    def __init__(self, account="123456789012", encrypted=True, unrelated=False, existing=False, price=15):
        self.account, self.encrypted, self.unrelated, self.existing, self.price = account, encrypted, unrelated, existing, price
        self.calls = []

    def __call__(self, *args):
        self.calls.append(args)
        action = args[:2]
        if action == ("sts", "get-caller-identity"):
            return {"Account": self.account}
        if action == ("lightsail", "get-container-service-powers"):
            return {"powers": [{"name": "small", "isActive": True, "price": 15, "ramSizeInGb": 1}]}
        if action == ("lightsail", "get-relational-database-bundles"):
            return {"bundles": [{"bundleId": "micro_2_0", "isActive": True, "isEncrypted": self.encrypted,
                                 "price": self.price, "ramSizeInGb": 1}]}
        if action == ("lightsail", "get-relational-database-blueprints"):
            return {"blueprints": [{"blueprintId": "postgres_17", "engine": "postgres", "engineVersion": "17.11", "isEngineDefault": False},
                                   {"blueprintId": "postgres_18", "engine": "postgres", "engineVersion": "18.6", "isEngineDefault": True}]}
        if action == ("cloudformation", "validate-template"):
            return {}
        if action == ("cloudformation", "describe-stacks"):
            if self.unrelated:
                return {"Stacks": [{"StackStatus": "CREATE_COMPLETE", "Tags": [{"Key": "Project", "Value": "unrelated"}]}]}
            if self.existing:
                return {"Stacks": [{"StackStatus": "CREATE_COMPLETE", "Tags": [{"Key": "Project", "Value": "black-box"},
                                        {"Key": "Purpose", "Value": "shared-container-prototype"}],
                    "Parameters": [{"ParameterKey": key, "ParameterValue": value} for key, value in
                                   {"ServiceName": "blackbox-cloud", "DatabaseBlueprint": "postgres_17", "DatabaseBundle": "micro_2_0",
                                    "DatabaseHost": "existing.example", "ContainerImage": ":blackbox-cloud.rprevious.1"}.items()]}]}
            raise RuntimeError("Stack blackbox-cloud does not exist")
        raise AssertionError("Unexpected AWS call: " + repr(args))


def args(**changes):
    values = dict(account_id="123456789012", region=deploy.APPROVED_REGION, stack_name="blackbox-cloud",
                  service_name="blackbox-cloud", image=None, prepare_only=True, rollback_image=None)
    values.update(changes)
    return SimpleNamespace(**values)


class SafetyTests(unittest.TestCase):
    def test_old_jar_without_auth_or_postgres_cannot_be_built(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "old.jar"
            with zipfile.ZipFile(path, "w") as package:
                package.writestr("BOOT-INF/classes/application.yml", "old sqlite-only service")
            with self.assertRaisesRegex(RuntimeError, "missing integrated PostgreSQL/auth"):
                build.inspect_jar(path)

    def test_integrated_jar_must_include_the_actual_jdbc_driver(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "integrated.jar"
            with zipfile.ZipFile(path, "w") as package:
                for name in build.REQUIRED_ENTRIES:
                    package.writestr(name, "fixture")
            with self.assertRaisesRegex(RuntimeError, "PostgreSQL JDBC driver"):
                build.inspect_jar(path)
            with zipfile.ZipFile(path, "a") as package:
                package.writestr("BOOT-INF/lib/postgresql-42.7.7.jar", "fixture")
            self.assertRegex(build.inspect_jar(path), r"^[0-9a-f]{64}$")

    def test_read_only_preflight_uses_current_encrypted_15_dollar_bundle(self):
        aws = FakeAws()
        plan = deploy.preflight(aws, args())
        self.assertEqual("31.20", plan["monthlyBaseUsd"])
        self.assertEqual("micro_2_0", plan["databaseBundle"])
        self.assertEqual("postgres_18", plan["databaseBlueprint"])
        self.assertTrue(all(command[1].startswith(("get-", "describe-", "validate-")) for command in aws.calls))

    def test_database_without_invented_encryption_field_matches_live_catalog(self):
        catalog = FakeAws()
        actual_shape = {"relationalDatabaseBundleId": "micro_2_0", "state": "available",
                        "masterEndpoint": {"address": "fixture.example", "port": 5432}}
        def aws(*command):
            if command[:2] == ("lightsail", "get-relational-database"):
                return {"relationalDatabase": actual_shape}
            return catalog(*command)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(actual_shape, deploy.wait_database(aws, "fixture-db", "micro_2_0"))
        self.assertIn("deployed bundle ID matched live encrypted bundle catalog", output.getvalue())

    def test_database_cannot_claim_encryption_from_a_different_bundle(self):
        def aws(*command):
            if command[:2] == ("lightsail", "get-relational-database"):
                return {"relationalDatabase": {"relationalDatabaseBundleId": "legacy_micro", "state": "available"}}
            return FakeAws()(*command)
        with self.assertRaisesRegex(RuntimeError, "does not match"):
            deploy.wait_database(aws, "fixture-db", "micro_2_0")

    def test_database_missing_or_unencrypted_catalog_record_fails_closed(self):
        for catalog in ({"bundles": []}, {"bundles": [{"bundleId": "micro_2_0", "isEncrypted": False}]}):
            with self.assertRaisesRegex(RuntimeError, "Cannot establish encryption"):
                deploy.wait_database(lambda *command: catalog, "fixture-db", "micro_2_0")

    def test_wrong_account_stops_before_other_api_reads(self):
        aws = FakeAws(account="999999999999")
        with self.assertRaisesRegex(RuntimeError, "different AWS account"):
            deploy.preflight(aws, args())
        self.assertEqual(1, len(aws.calls))

    def test_wrong_region_rejected_before_any_aws_call(self):
        aws = FakeAws()
        with self.assertRaisesRegex(RuntimeError, "authorized only"):
            deploy.preflight(aws, args(region="us-west-2"))
        self.assertEqual([], aws.calls)

    def test_old_unencrypted_15_dollar_bundle_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "do not fall back to unencrypted"):
            deploy.preflight(FakeAws(encrypted=False), args())

    def test_price_change_requires_review(self):
        with self.assertRaisesRegex(RuntimeError, "No active encrypted"):
            deploy.preflight(FakeAws(price=60), args())

    def test_existing_unrelated_stack_is_protected(self):
        with self.assertRaisesRegex(RuntimeError, "unrelated"):
            deploy.preflight(FakeAws(unrelated=True), args())

    def test_existing_database_blueprint_is_pinned(self):
        plan = deploy.preflight(FakeAws(existing=True), args())
        self.assertEqual("postgres_17", plan["databaseBlueprint"])
        self.assertEqual(":blackbox-cloud.rprevious.1", plan["previousParameters"]["ContainerImage"])

    def test_default_cli_never_calls_apply(self):
        with patch.object(sys, "argv", ["lightsail_deploy.py", "--profile", "fixture", "--account-id", "123456789012", "--prepare-only"]), \
             patch.object(deploy, "Aws", return_value=FakeAws()), patch.object(deploy, "apply") as write, \
             contextlib.redirect_stdout(io.StringIO()):
            deploy.main()
            write.assert_not_called()

    def test_high_level_cloudformation_deploy_accepts_text_output(self):
        output = SimpleNamespace(returncode=0, stdout="Waiting for changeset...\nSuccessfully created/updated stack", stderr="")
        with patch.object(subprocess, "run", return_value=output), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual({}, deploy.Aws("fixture", "us-east-2")("cloudformation", "deploy"))

    def test_push_parser_accepts_progress_before_registered_image(self):
        result = deploy.parse_image_push('Pushing layers...\n{"containerImage":{"image":":blackbox-cloud.ra1.2"}}\nDone')
        self.assertEqual(":blackbox-cloud.ra1.2", result["containerImage"]["image"])
        with self.assertRaisesRegex(RuntimeError, "do not guess"):
            deploy.parse_image_push("Pushed something without a version")

    def test_actual_lightsailctl_success_text_routes_through_aws_wrapper(self):
        # Exact fmt.Printf shape from aws/lightsailctl v1.0.8/internal/cs/pushimage.go.
        digest = "sha256:" + "b" * 64
        success = ('85fcec7ef3ef: Layer already exists\nDigest: ' + digest +
                   '\nImage "sha256:' + "a" * 64 + '" registered.\n' +
                   'Refer to this image as ":blackbox-cloud.raaaaaaaa.1" in deployments.\n')
        for stdout, stderr in ((success, ""), ("", success)):
            with self.subTest(channel="stdout" if stdout else "stderr"):
                result = SimpleNamespace(returncode=0, stdout=stdout, stderr=stderr)
                with patch.object(subprocess, "run", return_value=result), contextlib.redirect_stdout(io.StringIO()) as log:
                    parsed = deploy.Aws("fixture", "us-east-2")("lightsail", "push-container-image", "--service-name", "blackbox-cloud",
                                                                "--label", "raaaaaaaa", "--image", "sha256:" + "a" * 64)
                self.assertEqual({"containerImage": {"image": ":blackbox-cloud.raaaaaaaa.1", "digest": digest}}, parsed)
                self.assertEqual("", log.getvalue())

    def test_push_success_must_name_the_requested_service_and_label(self):
        for image in (":unrelated.raaaaaaaa.1", ":blackbox-cloud.wrong.1"):
            with self.assertRaisesRegex(RuntimeError, "does not match"):
                deploy.parse_image_push('Refer to this image as "' + image + '" in deployments.\n',
                                        service_name="blackbox-cloud", label="raaaaaaaa")

    def test_conflicting_push_versions_are_not_resolved_by_guessing_latest(self):
        output = 'Refer to this image as ":blackbox-cloud.raaaaaaaa.1" in deployments.\n'
        conflict = 'Refer to this image as ":blackbox-cloud.raaaaaaaa.2" in deployments.\n'
        with self.assertRaisesRegex(RuntimeError, "exactly one"):
            deploy.parse_image_push(output, conflict)
        with self.assertRaisesRegex(RuntimeError, "exactly one"):
            deploy.parse_image_push(output + 'Digest: sha256:' + 'a' * 64 + '\nDigest: sha256:' + 'b' * 64 + '\n')

    def test_unversioned_or_merely_mentioned_image_does_not_count_as_success(self):
        for output in ('Refer to this image as ":blackbox-cloud.raaaaaaaa.latest" in deployments.\n',
                       'Image ":blackbox-cloud.raaaaaaaa.1" registered.\n',
                       'Error: cannot refer to :blackbox-cloud.raaaaaaaa.1\n'):
            with self.assertRaisesRegex(RuntimeError, "do not guess"):
                deploy.parse_image_push(output)

    def test_image_push_uses_immutable_id_after_slow_database_preparation(self):
        selected = args(prepare_only=False, image="mutable:tag", image_id="sha256:" + "a" * 64, source_revision="reviewed")
        facts = deploy.preflight(FakeAws(), args())
        calls = []
        def aws(*command):
            calls.append(command)
            if command[:2] == ("lightsail", "push-container-image"):
                return {"containerImage": {"image": ":blackbox-cloud.ra1.1"}}
            raise AssertionError("Unexpected AWS call")
        with patch.object(deploy, "cfn_deploy"), \
             patch.object(deploy, "wait_database", return_value={"masterEndpoint": {"address": "db.example", "port": 5432}}), \
             patch.object(deploy, "require_database_tls", return_value={"plaintextStartupRejected": True}), \
             patch.object(deploy, "wait_container", return_value="https://blackbox.a.us-east-2.cs.amazonlightsail.com"), \
             patch.object(deploy, "stack_state", return_value={}), patch.object(deploy, "verify_public_auth", return_value={}), \
             contextlib.redirect_stdout(io.StringIO()):
            deploy.apply(aws, selected, facts)
        self.assertEqual(selected.image_id, calls[0][calls[0].index("--image") + 1])
        self.assertNotIn(selected.image, calls[0])

    def test_prepare_preserves_existing_app_image(self):
        parameters_seen = []
        facts = deploy.preflight(FakeAws(existing=True), args())
        with patch.object(deploy, "cfn_deploy", side_effect=lambda _aws, _args, params: parameters_seen.append(dict(params))), \
             patch.object(deploy, "wait_database", return_value={"masterEndpoint": {"address": "existing.example", "port": 5432}}), \
             patch.object(deploy, "require_database_tls", return_value={"certificateHostnameVerified": True, "plaintextStartupRejected": True}), \
             contextlib.redirect_stdout(io.StringIO()):
            deploy.apply(FakeAws(existing=True), args(), facts)
        self.assertEqual(1, len(parameters_seen))
        self.assertEqual(":blackbox-cloud.rprevious.1", parameters_seen[0]["ContainerImage"])

    def test_template_keeps_secrets_independent_and_uses_verified_tls(self):
        template = json.loads(deploy.TEMPLATE.read_text())
        resources = template["Resources"]
        generated = [resource for resource in resources.values() if resource["Type"] == "AWS::SecretsManager::Secret"]
        self.assertEqual(3, len(generated))
        self.assertTrue(all(resource["Properties"]["GenerateSecretString"]["PasswordLength"] >= 48 for resource in generated))
        database = resources["Database"]
        self.assertTrue(database["Properties"]["BackupRetention"])
        self.assertEqual("Retain", database["DeletionPolicy"])
        container = resources["Container"]["Properties"]
        self.assertEqual(("small", 1), (container["Power"], container["Scale"]))
        deployment = container["ContainerServiceDeployment"]["Fn::If"][1]
        env = {value["Variable"]: value["Value"] for value in deployment["Containers"][0]["Environment"]}
        self.assertIn("sslmode=verify-full", env["SBA_DATASOURCE_URL"]["Fn::Sub"])
        self.assertEqual("true", env["SBA_AUTH_ENABLED"])
        self.assertEqual("true", env["SBA_AUTH_SECURE_COOKIES"])
        self.assertNotIn("SERVER_FORWARD_HEADERS_STRATEGY", env)
        self.assertEqual(3, len({env[key]["Fn::Sub"] for key in ["SBA_DATASOURCE_PASSWORD", "SBA_AUTH_PASSWORD", "SBA_AUTH_API_TOKEN"]}))
        self.assertEqual("/actuator/health", deployment["PublicEndpoint"]["HealthCheckConfig"]["Path"])
        self.assertFalse(any(resource["Type"] in ("AWS::EC2::NatGateway", "AWS::ElasticLoadBalancingV2::LoadBalancer") for resource in resources.values()))

    def test_foreign_url_cannot_receive_auth_secret(self):
        aws = FakeAws()
        with self.assertRaisesRegex(RuntimeError, "Unexpected service URL"):
            deploy.verify_public_auth(aws, {}, "https://attacker.example")
        self.assertEqual([], aws.calls)

    def test_auth_failure_stops_before_secret_retrieval(self):
        aws = FakeAws()
        with patch.object(deploy, "http_status", return_value=200):
            with self.assertRaisesRegex(RuntimeError, "Authentication boundary failed"):
                deploy.verify_public_auth(aws, {}, "https://blackbox.a.us-east-2.cs.amazonlightsail.com")
        self.assertEqual([], aws.calls)

    def test_change_set_replacement_or_removal_never_executes(self):
        for action, replacement in (("Remove", "False"), ("Modify", "True"), ("Modify", "Conditional")):
            calls = []
            def aws(*command):
                calls.append(command)
                if command[1] == "create-change-set":
                    return {"Id": "change-fixture"}
                if command[1] == "describe-change-set":
                    return {"Status": "CREATE_COMPLETE", "Changes": [{"ResourceChange": {
                        "LogicalResourceId": "Database", "Action": action, "Replacement": replacement}}]}
                raise AssertionError("Unsafe execute reached: " + repr(command))
            with patch.object(deploy, "stack_state", return_value={"StackStatus": "CREATE_COMPLETE"}):
                with self.assertRaisesRegex(RuntimeError, "refusing automatic execution"):
                    deploy.cfn_deploy(aws, args(), {"ServiceName": "blackbox-cloud"})
            self.assertNotIn("execute-change-set", [call[1] for call in calls])

    def test_safe_change_set_executes_only_after_inspection(self):
        calls = []
        def aws(*command):
            calls.append(command)
            if command[1] == "create-change-set":
                return {"Id": "change-fixture"}
            if command[1] == "describe-change-set":
                return {"Status": "CREATE_COMPLETE", "Changes": [{"ResourceChange": {
                    "LogicalResourceId": "Container", "Action": "Modify", "Replacement": "False"}}]}
            return {}
        with patch.object(deploy, "stack_state", return_value={"StackStatus": "CREATE_COMPLETE"}), contextlib.redirect_stdout(io.StringIO()):
            deploy.cfn_deploy(aws, args(), {"ServiceName": "blackbox-cloud"})
        self.assertEqual(["create-change-set", "describe-change-set", "execute-change-set", "wait"], [call[1] for call in calls])

    def test_empty_change_set_is_idempotent_without_execute(self):
        calls = []
        def aws(*command):
            calls.append(command)
            if command[1] == "create-change-set":
                return {"Id": "empty-fixture"}
            if command[1] == "describe-change-set":
                return {"Status": "FAILED", "StatusReason": "The submitted information didn't contain changes."}
            return {}
        with patch.object(deploy, "stack_state", return_value={"StackStatus": "CREATE_COMPLETE"}), contextlib.redirect_stdout(io.StringIO()):
            deploy.cfn_deploy(aws, args(), {})
        self.assertEqual(["create-change-set", "describe-change-set", "delete-change-set"], [call[1] for call in calls])

    def test_plaintext_probe_rejects_password_challenge_and_sends_no_password(self):
        class Connection:
            def __init__(self, response):
                self.response, self.sent = response, []
            def __enter__(self): return self
            def __exit__(self, *unused): pass
            def sendall(self, value): self.sent.append(value)
            def recv(self, length):
                value, self.response = self.response[:length], self.response[length:]
                return value
        for kind, payload, expected in [(b"R", struct.pack("!I", 10), False),
                                         (b"E", b"SFATAL\x00C28000\x00Mno pg_hba.conf entry, no encryption\x00\x00", True),
                                         (b"E", b"SERROR\x00MUnknown user\x00\x00", False)]:
            connection = Connection(kind + struct.pack("!I", 4 + len(payload)) + payload)
            with patch.object(deploy.socket, "create_connection", return_value=connection):
                self.assertEqual(expected, deploy.verify_database_requires_tls("db.example", 5432))
            self.assertEqual(1, len(connection.sent))
            self.assertIn(b"user\x00blackbox\x00", connection.sent[0])
            self.assertNotIn(b"password", connection.sent[0])

    def test_existing_plaintext_database_is_not_rebooted_or_deployed(self):
        aws = FakeAws()
        with patch.object(deploy, "verify_database_tls", return_value={"databaseTls": "TLSv1.3"}), \
             patch.object(deploy, "verify_database_requires_tls", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "Existing database accepts plaintext"):
                deploy.require_database_tls(aws, "blackbox-cloud-db", "db.example", 5432, False)
        self.assertEqual([], aws.calls)

    def test_authenticated_http_redirect_is_not_followed(self):
        class Handler(BaseHTTPRequestHandler):
            destination_hits = 0
            def log_message(self, *unused): pass
            def do_GET(self):
                if self.path == "/start":
                    self.send_response(302)
                    self.send_header("Location", "/destination")
                else:
                    Handler.destination_hits += 1
                    self.send_response(200)
                self.end_headers()
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.assertEqual(302, deploy.http_status(f"http://127.0.0.1:{server.server_port}/start", "fixture-secret"))
            self.assertEqual(0, Handler.destination_hits)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_image_identity_and_nonroot_requirements(self):
        metadata = {"Id": "sha256:" + "a" * 64, "Architecture": "amd64", "Os": "linux", "Config": {
            "User": "10001:10001", "Labels": {"org.opencontainers.image.revision": "reviewed", "io.blackbox.cloud-contract": "postgres-auth-v1", "io.blackbox.jar-sha256": "b" * 64}}}
        completed = SimpleNamespace(stdout=json.dumps([metadata]))
        image_args = args(image="blackbox:reviewed", image_id=metadata["Id"], source_revision="reviewed")
        with patch.object(subprocess, "run", return_value=completed), patch.object(deploy.shutil, "which", return_value="/tmp/lightsailctl"):
            self.assertEqual("b" * 64, deploy.inspect_image(image_args)["jarSha256"])
            image_args.image_id = "sha256:" + "c" * 64
            with self.assertRaisesRegex(RuntimeError, "differs"):
                deploy.inspect_image(image_args)


if __name__ == "__main__":
    unittest.main()
