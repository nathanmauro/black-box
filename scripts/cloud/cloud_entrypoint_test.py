#!/usr/bin/env python3
"""Execute the cloud startup guard with fake Java; no JVM, database or Docker engine needed."""
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ENTRYPOINT = ROOT / "scripts/cloud/cloud-entrypoint.sh"


class CloudEntrypointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="blackbox-cloud-entrypoint-")
        self.addCleanup(self.temp.cleanup)
        self.fake_java = Path(self.temp.name) / "java"
        # A second shell could silently remove invalid environment names and hide a guard leak.
        # Inspect the actual exec environment directly; never print any environment values.
        self.fake_java.write_text(
            f'#!{sys.executable}\nimport os, sys\n'
            'print(f"JAVA_STARTED:{os.getpid()}")\n'
            'for arg in sys.argv[1:]: print(arg)\n'
            'probe = os.environ.get("FAKE_JAVA_PROBE")\n'
            'if probe: print("OVERRIDE_PRESENT" if probe in os.environ else "OVERRIDE_ABSENT")\n'
            'sys.exit(int(os.environ.get("FAKE_JAVA_EXIT", "0")))\n')
        self.fake_java.chmod(0o755)
        self.env = {
            "PATH": self.temp.name + ":/usr/bin:/bin",
            "SPRING_PROFILES_ACTIVE": "postgres",
            "SBA_DATASOURCE_URL": "jdbc:postgresql://fixture.invalid:5432/blackbox?sslmode=verify-full",
            "SBA_DATASOURCE_USERNAME": "fixture_user",
            "SBA_DATASOURCE_PASSWORD": secrets.token_urlsafe(40),
            "SBA_AUTH_ENABLED": "true",
            "SBA_AUTH_PASSWORD": secrets.token_urlsafe(40),
            "SBA_AUTH_API_TOKEN": secrets.token_urlsafe(40),
        }

    def run_guard(self, env=None, args=()):
        process = subprocess.Popen(["/bin/sh", str(ENTRYPOINT), *args], env=self.env if env is None else env,
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = process.communicate(timeout=5)
        return process.returncode, stdout, stderr, process.pid

    def assert_denied(self, env, args=()):
        code, stdout, stderr, _ = self.run_guard(env, args)
        self.assertEqual(code, 64)
        self.assertNotIn("JAVA_STARTED", stdout)
        self.assertIn("Black Box cloud startup refused:", stderr)
        for name in ("SBA_DATASOURCE_PASSWORD", "SBA_AUTH_PASSWORD", "SBA_AUTH_API_TOKEN"):
            if env.get(name):
                self.assertNotIn(env[name], stdout + stderr)
        return stderr

    def test_image_defaults_cannot_start_java(self):
        self.assert_denied({"PATH": self.env["PATH"], "SBA_BIND_ADDRESS": "0.0.0.0", "SBA_PORT": "8766"})

    def test_every_required_input_is_checked_before_java(self):
        for name in ("SPRING_PROFILES_ACTIVE", "SBA_DATASOURCE_URL", "SBA_DATASOURCE_USERNAME",
                     "SBA_DATASOURCE_PASSWORD", "SBA_AUTH_ENABLED", "SBA_AUTH_PASSWORD", "SBA_AUTH_API_TOKEN"):
            with self.subTest(name=name):
                env = dict(self.env)
                del env[name]
                self.assertIn(name, self.assert_denied(env))

    def test_wrong_profile_storage_or_auth_cannot_start(self):
        cases = {"SPRING_PROFILES_ACTIVE": ["sqlite", "postgres,other", ""],
                 "SBA_DATASOURCE_URL": ["jdbc:sqlite:/data/black-box.db", "jdbc:postgresql:blackbox", "https://fixture.invalid/db", "jdbc:postgresql://host/db\nsecret"],
                 "SBA_AUTH_ENABLED": ["false", "1", ""],
                 "SBA_AUTH_SECURE_COOKIES": ["false", ""]}
        for name, values in cases.items():
            for value in values:
                with self.subTest(name=name, value=value):
                    self.assert_denied(dict(self.env, **{name: value}))

    def test_missing_quality_or_identical_secrets_are_rejected_without_printing_them(self):
        for name in ("SBA_AUTH_PASSWORD", "SBA_AUTH_API_TOKEN"):
            for secret in ("short-fixture-secret", "has whitespace-" + "x" * 40, "a" * 35 + "\n"):
                with self.subTest(name=name):
                    self.assert_denied(dict(self.env, **{name: secret}))
        self.assert_denied(dict(self.env, SBA_AUTH_API_TOKEN=self.env["SBA_AUTH_PASSWORD"]))

    def test_configuration_override_channels_never_reach_java(self):
        names = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SPRING_APPLICATION_JSON",
                 "SPRING_PROFILES_INCLUDE", "SPRING_PROFILES_DEFAULT", "SPRING_CONFIG_LOCATION",
                 "SPRING_CONFIG_ADDITIONAL_LOCATION", "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_NAME",
                 "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
                 "SPRING_DATASOURCE_DRIVER_CLASS_NAME", "SPRING_DATASOURCE_HIKARI_JDBCURL",
                 "SPRING_DATASOURCE_HIKARI_JDBC_URL", "SPRING_DATASOURCE_HIKARI_USERNAME",
                 "SPRING_DATASOURCE_HIKARI_PASSWORD", "SPRING_DATASOURCE_HIKARI_DRIVERCLASSNAME",
                 "SPRING_DATASOURCE_HIKARI_DATASOURCECLASSNAME", "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_URL",
                 "SPRING_DATASOURCE_TYPE", "SPRING_DATASOURCE_JNDINAME", "SPRING_DATASOURCE_JNDI_NAME",
                 "spring.application.json", "spring_application_json", "spring.config.location",
                 "spring_config_location", "spring.config.additional-location", "spring_config_import",
                 "spring.profiles.include", "spring_profiles_default", "spring.profiles.active",
                 "spring.datasource.hikari.jdbc-url", "spring_datasource_hikari_jdbcurl", "SBA_STORAGE_BACKEND")
        for name in names:
            with self.subTest(name=name):
                marker = "private-override-" + secrets.token_hex(12)
                env = dict(self.env, **{name: marker})
                if name.isidentifier():
                    stderr = self.assert_denied(env)
                    self.assertNotIn(marker, stderr)
                else:
                    # dash drops non-shell environment names before executing the script;
                    # other POSIX shells preserve them, and the guard must reject those.
                    env["FAKE_JAVA_PROBE"] = name
                    code, stdout, stderr, _ = self.run_guard(env)
                    if code == 64:
                        self.assertNotIn("JAVA_STARTED", stdout)
                        self.assertIn("Black Box cloud startup refused:", stderr)
                    else:
                        self.assertEqual(code, 0)
                        self.assertIn("JAVA_STARTED", stdout)
                        self.assertIn("OVERRIDE_ABSENT", stdout)
                        self.assertNotIn("OVERRIDE_PRESENT", stdout)
                        self.assertEqual(stderr, "")
                    self.assertNotIn(marker, stdout + stderr)

    def test_command_arguments_cannot_override_auth_or_replace_the_process(self):
        for args in (("--SBA_AUTH_ENABLED=false",), ("sh", "-c", "echo secret")):
            self.assert_denied(self.env, args)

    def test_valid_config_execs_java_with_fixed_nonsecret_arguments_and_preserves_exit(self):
        env = dict(self.env, FAKE_JAVA_EXIT="23")
        code, stdout, stderr, pid = self.run_guard(env)
        self.assertEqual(code, 23)
        self.assertEqual(stderr, "")
        self.assertEqual(stdout.splitlines()[0], f"JAVA_STARTED:{pid}")
        self.assertEqual(stdout.splitlines()[1:], [
            "-Xms128m", "-Xmx512m", "-Xss512k", "-XX:MaxMetaspaceSize=192m",
            "-XX:ReservedCodeCacheSize=64m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar",
            "--spring.config.location=classpath:/application.yml",
            "--spring.profiles.active=postgres", "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--sba.storage.backend=postgres", "--SBA_AUTH_ENABLED=true", "--SBA_AUTH_SECURE_COOKIES=true"])
        for key, value in self.env.items():
            if key.endswith("PASSWORD") or key.endswith("TOKEN"):
                self.assertNotIn(value, stdout + stderr)

    def test_cloud_dockerfile_uses_guard_without_sqlite_fallback(self):
        dockerfile = (ROOT / "Dockerfile.cloud").read_text()
        line = next(line for line in dockerfile.splitlines() if line.startswith("ENTRYPOINT "))
        self.assertEqual(json.loads(line[len("ENTRYPOINT "):]), ["/bin/sh", "/app/cloud-entrypoint.sh"])
        self.assertIn("COPY --chmod=0555 scripts/cloud/cloud-entrypoint.sh /app/cloud-entrypoint.sh", dockerfile)
        self.assertNotIn("jdbc:sqlite", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)
        self.assertIn('io.blackbox.cloud-contract="postgres-auth-v1"', dockerfile)

    def test_build_context_reexcludes_directory_contents_before_exact_allows(self):
        # Docker's !directory rule also includes its children. Explicit re-exclusion is essential;
        # the exact policy was additionally exercised with a scratch-only real Docker build.
        self.assertEqual((ROOT / ".dockerignore").read_text().splitlines(), [
            "*", "!target/", "target/*", "!target/*.jar", "!scripts/", "scripts/*",
            "!scripts/cloud/", "scripts/cloud/*", "!scripts/cloud/cloud-entrypoint.sh"])


if __name__ == "__main__":
    unittest.main()
