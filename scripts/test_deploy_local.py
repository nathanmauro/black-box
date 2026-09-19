#!/usr/bin/env python3
"""Exercise deploy-local.sh through fake commands and disposable installation files."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
FAKE = r'''#!/usr/bin/env python3
import hashlib, json, os, pathlib, shutil, signal, sys
p = pathlib.Path(os.environ['BB_DEPLOY_FIXTURE'])
s = json.loads((p/'state.json').read_text())
cmd = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with (p/'calls.log').open('a') as log: log.write(cmd+' '+ ' '.join(args)+'\n')
def save(): (p/'state.json').write_text(json.dumps(s))
def fail(): sys.exit(1)
jar = pathlib.Path(s['jar'])
def old(): return jar.is_file() and hashlib.sha256(jar.read_bytes()).hexdigest() == s['old_hash']
if cmd == 'uname': print('Darwin')
elif cmd == 'launchctl':
    op = args[0]
    if op == 'print':
        if not s['registered']:
            print('Could not find service "com.test.blackbox" in domain', file=sys.stderr); sys.exit(113)
        print('path = '+s['plist'])
        print('program = '+('/different/java' if s['scenario'] == 'program_mismatch' else s['arguments'][0]))
        print('working directory = '+ ('/different' if s['scenario'] == 'runtime_mismatch' else s['cwd']))
        print('\targuments = {\n'+''.join('\t\t'+arg+'\n' for arg in s['arguments'])+'\t}')
        print('\tenvironment = {\n'+''.join('\t\t'+key+' => '+value+'\n' for key,value in s['environment'].items())+'\t}')
        if s['alive']: print('pid = '+str(s['pid']))
    elif op == 'bootout':
        if s['scenario'] == 'stop_failure': fail()
        s['registered'] = False
        if s['scenario'] != 'lingering': s['alive'] = False
        save()
    elif op == 'bootstrap':
        if not jar.is_file(): fail()
        if not old() and s['scenario'] in ('bootstrap_failure', 'rollback_failure'): fail()
        if old() and s['scenario'] == 'rollback_failure': fail()
        s.update(registered=True, alive=True, pid=s['pid']+1)
        save()
        if not old() and s['scenario'] == 'interrupt_bootstrap': os.kill(os.getppid(), signal.SIGTERM)
    elif op == 'kickstart':
        if not s['registered']: fail()
elif cmd == 'ps':
    if '-p' in args:
        pid = int(args[args.index('-p')+1])
        if not s['alive'] or pid != s['pid']: fail()
    if s['alive']:
        if 'pid=' in args: print(s['pid'])
        else: print(str(s['pid'])+' /usr/bin/java -jar '+s['jar'])
elif cmd == 'lsof':
    if '-nP' not in args and not jar.exists():
        print('lsof: status error on '+str(jar)+': No such file or directory', file=sys.stderr); fail()
    if not s['alive']: fail()
    print(999 if s['scenario'] == 'wrong_listener' and '-nP' in args else s['pid'])
elif cmd == 'curl':
    if s['scenario'] == 'unhealthy_initial': fail()
    if not s['alive'] or (not old() and s['scenario'] in ('readiness_failure', 'rollback_failure')): fail()
    print('{"error": "down"}' if s['scenario'] == 'wrong_json' else '{"storage": {"sessions": 1, "events": 1}}')
    print('302' if s['scenario'] == 'redirect' else '200', end='')
elif cmd == 'mvn':
    if s['alive']:
        (p/'unsafe').write_text('Maven ran while JVM alive'); sys.exit(9)
    jar.unlink()
    if s['scenario'] in ('build_failure', 'interrupt'):
        if s['scenario'] == 'interrupt': os.kill(os.getppid(), signal.SIGTERM)
        sys.exit(7)
    shutil.copy2(p/'new.jar', jar)
elif cmd == 'cp':
    if s['alive']:
        (p/'unsafe').write_text('Install ran while JVM alive'); sys.exit(9)
    if s['scenario'] == 'install_failure' and pathlib.Path(args[-2]).name == 'candidate.jar': fail()
    shutil.copy2(args[-2], args[-1])
elif cmd == 'sleep': pass
elif cmd == 'stat': print('fake stat')
else: raise RuntimeError(cmd)
'''


def archive(path, marker):
    with zipfile.ZipFile(path, 'w') as out:
        out.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\nStart-Class: dev.nathan.sbaagentic.SbaAgenticApplication\r\n\r\n')
        out.writestr('org/springframework/boot/loader/launch/JarLauncher.class', b'loader')
        out.writestr('BOOT-INF/classes/dev/nathan/sbaagentic/SbaAgenticApplication.class', marker)
        out.writestr('BOOT-INF/lib/library.jar', b'library')


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='blackbox-deploy-test-')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve()
        self.repo = self.base / 'checkout'
        (self.repo / 'scripts').mkdir(parents=True)
        (self.repo / 'target').mkdir()
        for name in ('deploy-local.sh', 'deploy_local.py'):
            if (ROOT / 'scripts' / name).exists(): shutil.copy2(ROOT / 'scripts' / name, self.repo / 'scripts' / name)
        (self.repo / 'pom.xml').write_text('<project><artifactId>sba-agentic</artifactId><version>0.1.0</version></project>')
        self.jar = self.repo / 'target/sba-agentic-0.1.0.jar'
        archive(self.jar, b'old')
        archive(self.base / 'new.jar', b'new')
        self.old = self.jar.read_bytes()
        self.plist = self.base / 'service.plist'
        self.definition = {'Label': 'com.test.blackbox', 'WorkingDirectory': str(self.repo), 'ProgramArguments': ['/usr/bin/java', '-jar', str(self.jar)], 'EnvironmentVariables': {'SBA_PORT': '9999'}, 'RunAtLoad': True}
        self.plist.write_bytes(plistlib.dumps(self.definition))
        self.plist_bytes = self.plist.read_bytes()
        self.state = {'scenario': 'success', 'registered': True, 'alive': True, 'pid': 111, 'jar': str(self.jar), 'plist': str(self.plist), 'old_hash': hashlib.sha256(self.old).hexdigest(), 'arguments': self.definition['ProgramArguments'], 'environment': self.definition['EnvironmentVariables'], 'cwd': str(self.repo)}
        self.bin = self.base / 'bin'; self.bin.mkdir()
        fake = self.bin / 'fake'
        fake.write_text(FAKE.replace('#!/usr/bin/env python3', '#!'+sys.executable, 1)); fake.chmod(0o755)
        for name in ('uname', 'launchctl', 'ps', 'lsof', 'curl', 'mvn', 'cp', 'sleep', 'stat'): (self.bin / name).symlink_to(fake)
        self.env = dict(os.environ, PATH=str(self.bin)+os.pathsep+os.environ['PATH'], BB_DEPLOY_FIXTURE=str(self.base), SBA_LAUNCHD_LABEL='com.test.blackbox', SBA_LAUNCHD_DOMAIN='gui/'+str(os.getuid()), SBA_LAUNCHD_PLIST=str(self.plist), SBA_JAR_PATH=str(self.jar), SBA_STATUS_URL='http://127.0.0.1:9999/api/status', SBA_DEPLOY_STOP_TIMEOUT='0.2', SBA_DEPLOY_READY_TIMEOUT='0.2')

    def deploy(self, scenario='success', prebuilt=False):
        self.state['scenario'] = scenario
        (self.base / 'state.json').write_text(json.dumps(self.state))
        (self.base / 'calls.log').write_text('')
        args = [str(self.repo / 'scripts/deploy-local.sh')]
        if prebuilt: args += ['--prebuilt-jar', str(self.base / 'new.jar')]
        result = subprocess.run(args, env=self.env, capture_output=True, text=True, timeout=15)
        self.calls = (self.base / 'calls.log').read_text()
        self.assertEqual(self.plist.read_bytes(), self.plist_bytes, result.stdout+result.stderr)
        self.assertFalse((self.base / 'unsafe').exists(), result.stdout+result.stderr)
        return result

    def assert_restored(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertEqual(self.jar.read_bytes(), self.old, result.stdout+result.stderr)
        state = json.loads((self.base / 'state.json').read_text())
        self.assertTrue(state['alive'] and state['registered'], result.stdout+result.stderr)
        self.assertIn('Rollback verified', result.stdout+result.stderr)

    def test_rebuild_success(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertEqual(self.jar.read_bytes(), (self.base / 'new.jar').read_bytes())
        self.assertIn('mvn -q clean -Pfrontend -DskipTests package', self.calls)

    def test_prebuilt_success_never_runs_maven(self):
        result = self.deploy(prebuilt=True)
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertNotIn('mvn ', self.calls)
        self.assertEqual(self.jar.read_bytes(), (self.base / 'new.jar').read_bytes())

    def test_build_failure_after_old_jar_deleted(self): self.assert_restored(self.deploy('build_failure'))
    def test_interruption_after_old_jar_deleted(self): self.assert_restored(self.deploy('interrupt'))
    def test_install_failure(self): self.assert_restored(self.deploy('install_failure', prebuilt=True))
    def test_interruption_after_candidate_started(self): self.assert_restored(self.deploy('interrupt_bootstrap', prebuilt=True))
    def test_bootstrap_failure(self): self.assert_restored(self.deploy('bootstrap_failure', prebuilt=True))
    def test_readiness_failure(self): self.assert_restored(self.deploy('readiness_failure', prebuilt=True))

    def test_stop_failure_never_rebuilds_or_replaces(self):
        result = self.deploy('stop_failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('mvn ', self.calls)
        self.assertEqual(self.jar.read_bytes(), self.old)

    def test_lingering_jvm_never_rebuilds_or_replaces(self):
        result = self.deploy('lingering')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('mvn ', self.calls)
        self.assertEqual(self.jar.read_bytes(), self.old)

    def test_invalid_archive_never_stops(self):
        (self.base / 'new.jar').write_text('not a boot jar')
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_rollback_failure_retains_recovery(self):
        result = self.deploy('rollback_failure', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Recovery retained at:', result.stdout+result.stderr)
        backups = list(self.base.glob('.blackbox-deploy/**/previous.jar'))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), self.old)

    def test_disagreeing_jar_override_rejected(self):
        self.env['SBA_JAR_PATH'] = str(self.base / 'different.jar')
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_jar_alias_rejected(self):
        alias = self.base / 'alias.jar'; alias.symlink_to(self.jar)
        self.definition['ProgramArguments'][-1] = str(alias)
        self.plist.write_bytes(plistlib.dumps(self.definition)); self.plist_bytes = self.plist.read_bytes()
        self.env['SBA_JAR_PATH'] = str(alias)
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)


    def test_lock_blocks_second_deployment(self):
        state_dir = self.base / '.blackbox-deploy' / hashlib.sha256(str(self.jar).encode()).hexdigest()[:20]
        state_dir.parent.mkdir(mode=0o700)
        state_dir.mkdir(mode=0o700)
        with (state_dir / 'deployment.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Another deployment', result.stderr)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_loaded_program_mismatch_never_stops(self):
        result = self.deploy('program_mismatch', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('program differs from the installed plist', result.stderr)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_loaded_definition_mismatch_never_stops(self):
        result = self.deploy('runtime_mismatch', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('differ from the installed plist', result.stderr)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_wrong_readiness_shape_never_stops(self):
        result = self.deploy('wrong_json', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_http_redirect_never_stops(self):
        result = self.deploy('redirect', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_wrong_listener_never_stops(self):
        result = self.deploy('wrong_listener', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_unhealthy_initial_service_never_stops(self):
        result = self.deploy('unhealthy_initial', prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_mismatched_label_never_stops(self):
        self.env['SBA_LAUNCHD_LABEL'] = 'other.service'
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_mismatched_port_never_stops(self):
        self.env['SBA_STATUS_URL'] = 'http://127.0.0.1:9998/api/status'
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_rebuild_requires_the_installed_checkout(self):
        other = self.base / 'other-checkout'; other.mkdir()
        self.definition['WorkingDirectory'] = str(other)
        self.plist.write_bytes(plistlib.dumps(self.definition)); self.plist_bytes = self.plist.read_bytes()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('mvn ', self.calls)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_plain_zip_is_not_a_boot_archive(self):
        with zipfile.ZipFile(self.base / 'new.jar', 'w') as archive:
            archive.writestr('README.txt', 'not executable')
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)

    def test_hard_linked_jar_never_stops(self):
        os.link(self.jar, self.base / 'hardlink.jar')
        result = self.deploy(prebuilt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('launchctl bootout', self.calls)


if __name__ == '__main__': unittest.main(verbosity=2)
