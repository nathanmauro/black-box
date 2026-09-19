#!/usr/bin/env python3
"""Replace one existing launchd server JAR, retaining a verified binary rollback."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import zipfile


class DeployError(Exception):
    pass


def run(*args, timeout=30):
    # Stop build children too when an interruption initiates rollback.
    with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, start_new_session=True) as child:
        try:
            stdout, stderr = child.communicate(timeout=timeout)
        except BaseException:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            raise
        return subprocess.CompletedProcess(args, child.returncode, stdout, stderr)


def checked(*args, timeout=30):
    result = run(*args, timeout=timeout)
    if result.returncode:
        raise DeployError(f'{Path(args[0]).name} {args[1] if len(args) > 1 else ""} failed (exit {result.returncode})')
    return result.stdout


def digest(path):
    with path.open('rb') as source:
        return hashlib.sha256(source.read()).hexdigest()


def canonical(value, kind='file'):
    path = Path(value)
    if not path.is_absolute() or str(path) != str(path.resolve()):
        raise DeployError(f'{kind} must be an absolute canonical path without aliases: {path}')
    if kind == 'directory' and not path.is_dir():
        raise DeployError(f'Directory does not exist: {path}')
    if kind != 'directory' and (not path.is_file() or path.stat().st_nlink != 1):
        raise DeployError(f'{kind} must be an existing regular file without hard links: {path}')
    return path


def boot_archive(path):
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError('invalid CRC')
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError('duplicate entries')
            manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '').replace('\r\n', '\n')
            values = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
            main = values.get('Main-Class', '')
            start = values.get('Start-Class', '')
            if main != 'org.springframework.boot.loader.launch.JarLauncher' or start != 'dev.nathan.sbaagentic.SbaAgenticApplication':
                raise ValueError('not the Black Box Boot application')
            if main.replace('.', '/')+'.class' not in names or 'BOOT-INF/classes/'+start.replace('.', '/')+'.class' not in names:
                raise ValueError('missing application/launcher')
            if not any(name.startswith('BOOT-INF/lib/') and name.endswith('.jar') for name in names):
                raise ValueError('missing Boot libraries')
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise DeployError(f'Invalid Spring Boot archive: {path} ({error})') from error


class Deployment:
    def __init__(self, args):
        self.args = args
        self.repo = Path(__file__).resolve().parent.parent
        label_override = os.environ.get('SBA_LAUNCHD_LABEL')
        selected_label = label_override or 'com.nathan.sba-agentic'
        self.plist = canonical(os.environ.get('SBA_LAUNCHD_PLIST', str(Path.home() / 'Library/LaunchAgents' / (selected_label+'.plist'))), 'plist')
        self.plist_bytes = self.plist.read_bytes()
        definition = plistlib.loads(self.plist_bytes)
        self.label = definition.get('Label', '')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', self.label) or (label_override and label_override != self.label):
            raise DeployError('The label override must agree with the installed plist')
        self.domain = os.environ.get('SBA_LAUNCHD_DOMAIN', f'gui/{os.getuid()}')
        if self.domain != f'gui/{os.getuid()}':
            raise DeployError('Only the current user GUI launchd domain is supported')
        self.service = self.domain+'/'+self.label
        self.cwd = canonical(definition.get('WorkingDirectory', ''), 'directory')
        argv = definition.get('ProgramArguments', [])
        self.loaded_arguments = list(argv) if isinstance(argv, list) else []
        self.loaded_program = definition.get('Program', self.loaded_arguments[0] if self.loaded_arguments else '')
        self.loaded_environment = dict(definition.get('EnvironmentVariables', {}))
        env = dict(definition.get('EnvironmentVariables', {}))
        if not isinstance(argv, list) or not argv or not all(isinstance(a, str) for a in argv):
            raise DeployError('The plist needs explicit ProgramArguments')
        if Path(argv[0]).name == 'env':
            argv = argv[1:]
            while argv and re.match(r'^[A-Za-z_][A-Za-z_0-9]*=', argv[0]):
                name, value = argv.pop(0).split('=', 1)
                env[name] = value
        if len(argv) != 3 or Path(argv[0]).name != 'java' or argv[1] != '-jar':
            raise DeployError('Only an explicit java -jar server command (optionally env KEY=value) is supported')
        if definition.get('Program', definition['ProgramArguments'][0]) != definition['ProgramArguments'][0]:
            raise DeployError('The plist Program and ProgramArguments disagree')
        if any('\n' in arg or '\r' in arg for arg in self.loaded_arguments) or any(not isinstance(value, str) or '\n' in value or '\r' in value for value in self.loaded_environment.values()):
            raise DeployError('Multiline or non-string launchd arguments/environment are unsupported')
        self.jar = canonical(argv[2], 'JAR')
        if 'SBA_JAR_PATH' in os.environ and os.environ['SBA_JAR_PATH'] != str(self.jar):
            raise DeployError('SBA_JAR_PATH must exactly match the installed plist')
        self.port = int(env.get('SERVER_PORT', env.get('SBA_PORT', '8766')))
        if not 1 <= self.port <= 65535:
            raise DeployError('Invalid installed server port')
        if 'SBA_PORT' in os.environ and int(os.environ['SBA_PORT']) != self.port:
            raise DeployError('SBA_PORT must agree with the installed plist')
        self.url = os.environ.get('SBA_STATUS_URL', f'http://127.0.0.1:{self.port}/api/status')
        url = urlsplit(self.url)
        if url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost', '::1') or (url.port or 80) != self.port or url.path != '/api/status' or url.query or url.fragment or url.username or url.password:
            raise DeployError('Readiness must use the installed local port and /api/status without credentials')
        if not args.prebuilt_jar:
            if self.repo != self.cwd:
                raise DeployError('Rebuild checkout must be the installed WorkingDirectory; use --prebuilt-jar for an isolated build')
            pom = ET.parse(self.repo / 'pom.xml').getroot()
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'} if pom.tag.startswith('{') else {}
            prefix = 'm:' if ns else ''
            def value(name):
                return pom.findtext('/'.join(prefix+p for p in name.split('/')), namespaces=ns)
            artifact, version = value('artifactId'), value('version')
            if value('build/directory') or value('build/finalName') or not artifact or not version or '${' in artifact+version:
                raise DeployError('Rebuild requires a fixed Maven artifact/version and default target output')
            if self.jar != self.repo / 'target' / (artifact+'-'+version+'.jar'):
                raise DeployError('Maven output and installed JAR disagree')
        self.prebuilt = canonical(args.prebuilt_jar, 'candidate JAR') if args.prebuilt_jar else None
        if self.prebuilt == self.jar:
            raise DeployError('The candidate must be built outside the live artifact path')
        self.stop_timeout = float(os.environ.get('SBA_DEPLOY_STOP_TIMEOUT', '20'))
        self.ready_timeout = float(os.environ.get('SBA_DEPLOY_READY_TIMEOUT', '60'))
        if not (0 < self.stop_timeout <= 300 and 0 < self.ready_timeout <= 600):
            raise DeployError('Deployment timeouts must be positive and bounded (stop <= 300s, readiness <= 600s)')
        # Stable per-artifact lock outside target/ and outside the checkout itself.
        self.state_root = self.cwd.parent / '.blackbox-deploy' / hashlib.sha256(str(self.jar).encode()).hexdigest()[:20]
        self.recovery = None
        self.old_pid = None

    def unchanged_plist(self):
        if self.plist.read_bytes() != self.plist_bytes:
            raise DeployError('Installed plist changed during deployment; refusing a different service definition')

    def service_pid(self):
        result = run('launchctl', 'print', self.service)
        if result.returncode:
            if 'Could not find service' in result.stderr:
                return None
            raise DeployError('Cannot determine launchd service state')
        paths = re.findall(r'^\s*path = (.+)$', result.stdout, re.M)
        if paths != [str(self.plist)]:
            raise DeployError('Loaded launchd service does not match the selected plist path')
        program = re.findall(r'^\s*program = (.+)$', result.stdout, re.M)
        if program != [self.loaded_program]:
            raise DeployError('Loaded launchd program differs from the installed plist')
        cwd = re.findall(r'^\s*working directory = (.+)$', result.stdout, re.M)
        def block(name):
            match = re.search(r'^\t'+re.escape(name)+r' = \{\n(.*?)\n\t\}', result.stdout, re.M | re.S)
            return [line.strip() for line in match.group(1).splitlines()] if match else []
        if cwd != [str(self.cwd)] or block('arguments') != self.loaded_arguments:
            raise DeployError('Loaded launchd arguments or working directory differ from the installed plist')
        effective_env = {}
        for name in ('default environment', 'inherited environment', 'environment'):
            for line in block(name):
                if ' => ' not in line:
                    raise DeployError('Cannot parse loaded launchd environment')
                key, value = line.split(' => ', 1)
                effective_env[key] = value
        if any(effective_env.get(key) != value for key, value in self.loaded_environment.items()):
            raise DeployError('Loaded launchd environment differs from the installed plist')
        sensitive_prefixes = ('SBA_', 'SPRING_', 'SERVER_')
        jvm_keys = ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')
        if any((key.startswith(sensitive_prefixes) or key in jvm_keys) and key not in self.loaded_environment for key in effective_env):
            raise DeployError('Inherited application/JVM environment is not pinned in the installed plist')
        pids = re.findall(r'^\s*pid = ([0-9]+)$', result.stdout, re.M)
        if len(pids) > 1:
            raise DeployError('Ambiguous launchd PID')
        return int(pids[0]) if pids else 0

    def pid_exists(self, pid):
        result = run('ps', '-p', str(pid), '-o', 'pid=')
        if result.returncode not in (0, 1) or result.stderr:
            raise DeployError('Cannot determine process state')
        return bool(result.stdout.strip())

    def consumers(self):
        pids = set()
        # Maven clean can remove the destination. lsof reports a missing path as an
        # error; the recorded PID and -jar process scan still guard restoration.
        if self.jar.exists():
            result = run('lsof', '-t', '--', str(self.jar))
            if result.returncode not in (0, 1) or result.stderr:
                raise DeployError('Cannot determine JAR file consumers')
            pids.update(int(pid) for pid in result.stdout.split())
        # Also detect -jar users before/after the archive is opened by the JVM.
        output = checked('ps', 'axww', '-o', 'pid=,command=')
        pattern = re.compile(r'(?:^|\s)-jar\s+'+re.escape(str(self.jar))+r'(?:\s|$)')
        for line in output.splitlines():
            if pattern.search(line):
                pids.add(int(line.split(None, 1)[0]))
        return pids

    def health(self, pid):
        if not pid or not self.pid_exists(pid) or pid not in self.consumers():
            return False
        listener = run('lsof', '-nP', f'-iTCP:{self.port}', '-sTCP:LISTEN', '-t')
        if listener.returncode not in (0, 1) or listener.stderr:
            raise DeployError('Cannot verify readiness port ownership')
        if set(listener.stdout.split()) != {str(pid)}:
            return False
        result = run('curl', '--noproxy', '*', '--max-time', '2', '--fail', '--silent', '--show-error', '--write-out', '\n%{http_code}', self.url)
        if result.returncode:
            return False
        try:
            payload, separator, code = result.stdout.rpartition('\n')
            if not separator or code != '200':
                return False
            body = json.loads(payload)
            storage = body.get('storage', {}) if isinstance(body, dict) else {}
            return isinstance(storage, dict) and all(type(storage.get(key)) is int and storage[key] >= 0 for key in ('events', 'sessions'))
        except ValueError:
            return False

    def stopped(self, prior_pid):
        return self.service_pid() is None and (not prior_pid or not self.pid_exists(prior_pid)) and not self.consumers()

    def stop(self):
        self.unchanged_plist()
        pid = self.service_pid()
        if pid is not None:
            checked('launchctl', 'bootout', self.service)
        deadline = time.monotonic()+self.stop_timeout
        while True:
            if self.stopped(pid):
                return
            if time.monotonic() >= deadline:
                raise DeployError('Service or JAR consumers remain alive; artifact replacement refused')
            time.sleep(0.1)

    def install(self, source, expected_hash):
        self.unchanged_plist()
        if not self.stopped(self.old_pid):
            raise DeployError('Service must remain stopped before artifact replacement')
        if digest(source) != expected_hash:
            raise DeployError('Staged JAR checksum changed')
        self.jar.parent.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix='.'+self.jar.name+'.install-', dir=self.jar.parent)
        os.close(fd)
        pending = Path(name)
        try:
            checked('cp', '-p', str(source), str(pending))
            if digest(pending) != expected_hash:
                raise DeployError('Installed copy checksum mismatch')
            with pending.open('rb') as staged:
                os.fsync(staged.fileno())
            if not self.stopped(self.old_pid):
                raise DeployError('A JAR consumer appeared before atomic replacement')
            os.replace(pending, self.jar)
            if digest(self.jar) != expected_hash:
                raise DeployError('Installed JAR checksum mismatch')
        finally:
            pending.unlink(missing_ok=True)

    def start(self, expected_hash):
        self.unchanged_plist()
        if digest(self.jar) != expected_hash:
            raise DeployError('Refusing to start an unexpected artifact')
        checked('launchctl', 'bootstrap', self.domain, str(self.plist))
        checked('launchctl', 'kickstart', self.service)
        deadline = time.monotonic()+self.ready_timeout
        while True:
            pid = self.service_pid()
            if pid and pid != self.old_pid and self.health(pid):
                if self.service_pid() == pid and digest(self.jar) == expected_hash:
                    return pid
            if time.monotonic() >= deadline:
                raise DeployError('New service did not become ready on its owned port')
            time.sleep(0.1)

    def rollback(self):
        print('Deployment failed; restoring the previous binary.', file=sys.stderr)
        # A rejected bootout may have left the original process healthy and untouched.
        if self.jar.is_file() and digest(self.jar) == self.old_hash and self.service_pid() == self.old_pid and self.health(self.old_pid):
            print('Rollback verified: original process and artifact remained healthy.', file=sys.stderr)
            return
        self.stop()
        self.install(self.recovery / 'previous.jar', self.old_hash)
        pid = self.start(self.old_hash)
        print(f'Rollback verified: PID {pid}, SHA-256 {self.old_hash}', file=sys.stderr)

    def execute_locked(self):
        boot_archive(self.jar)
        if self.prebuilt:
            boot_archive(self.prebuilt)
        self.old_pid = self.service_pid()
        if not self.old_pid or not self.health(self.old_pid):
            raise DeployError('The existing service must be running and healthy before deployment')
        if self.consumers() != {self.old_pid}:
            raise DeployError('Other JAR consumers exist; stop them separately before deployment')
        self.recovery = Path(tempfile.mkdtemp(prefix='recovery-', dir=self.state_root))
        shutil.copy2(self.jar, self.recovery / 'previous.jar')
        self.old_hash = digest(self.jar)
        if digest(self.recovery / 'previous.jar') != self.old_hash:
            raise DeployError('Recovery copy checksum mismatch')
        (self.recovery / 'installed.plist').write_bytes(self.plist_bytes)
        candidate = self.recovery / 'candidate.jar'
        if self.prebuilt:
            shutil.copy2(self.prebuilt, candidate)
            boot_archive(candidate)
            self.new_hash = digest(candidate)
        (self.recovery / 'recovery.json').write_text(json.dumps({'service': self.service, 'plist': str(self.plist), 'jar': str(self.jar), 'previousSha256': self.old_hash, 'previousPid': self.old_pid}, indent=2)+'\n')
        print(f'Recovery retained at: {self.recovery}', flush=True)
        print(f'Stopping {self.service}; previous SHA-256 {self.old_hash}', flush=True)
        try:
            self.stop()
            if not self.prebuilt:
                # Clean may remove target/ entirely; recovery remains outside it.
                if not self.stopped(self.old_pid):
                    raise DeployError('JAR consumers appeared before Maven')
                result = run('mvn', '-q', 'clean', '-Pfrontend', *([] if self.args.with_tests else ['-DskipTests']), 'package', timeout=1200)
                (self.recovery / 'build.log').write_text(result.stdout+result.stderr)
                if result.returncode:
                    raise DeployError('Maven failed; build log is retained with recovery files')
                canonical(str(self.jar), 'built JAR')
                boot_archive(self.jar)
                shutil.copy2(self.jar, candidate)
                self.new_hash = digest(candidate)
            receipt = self.recovery / 'recovery.json'
            metadata = json.loads(receipt.read_text())
            metadata['candidateSha256'] = self.new_hash
            receipt.write_text(json.dumps(metadata, indent=2)+'\n')
            self.install(candidate, self.new_hash)
            pid = self.start(self.new_hash)
            print(f'Deployment verified: PID {pid}, SHA-256 {self.new_hash}, {self.url}')
        except BaseException:
            for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                signal.signal(signum, signal.SIG_IGN)
            try:
                self.rollback()
            except BaseException as error:
                print(f'ROLLBACK NOT VERIFIED: {error}', file=sys.stderr)
                print(f'Recovery retained at: {self.recovery}\nKeep the service stopped before restoring previous.jar to {self.jar}; use the unchanged installed plist and verify its PID and /api/status. Database recovery is separate.', file=sys.stderr)
            raise

    def execute(self):
        # Never traverse a preexisting state-directory symlink.
        for directory in (self.state_root.parent, self.state_root):
            if directory.is_symlink():
                raise DeployError('Deployment state directory must not be a symlink')
            directory.mkdir(mode=0o700, exist_ok=True)
            if directory.stat().st_uid != os.getuid() or stat.S_IMODE(directory.stat().st_mode) != 0o700:
                raise DeployError('Deployment state directory must be owned by this user with mode 0700')
        lock = self.state_root / 'deployment.lock'
        fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        lock_stat = os.fstat(fd)
        if not stat.S_ISREG(lock_stat.st_mode) or lock_stat.st_uid != os.getuid() or lock_stat.st_nlink != 1:
            os.close(fd)
            raise DeployError('Deployment lock must be a regular file owned by this user without hard links')
        with os.fdopen(fd, 'w') as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise DeployError('Another deployment holds this installation lock')
            handle.write(str(os.getpid())+'\n'); handle.flush()
            os.chdir(self.cwd)
            self.execute_locked()


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog='Installation selectors: SBA_LAUNCHD_PLIST, SBA_LAUNCHD_LABEL, SBA_LAUNCHD_DOMAIN. Assertions: SBA_JAR_PATH, SBA_PORT, SBA_STATUS_URL must agree with the plist. Timeouts: SBA_DEPLOY_STOP_TIMEOUT (20s), SBA_DEPLOY_READY_TIMEOUT (60s). See docs/operations.md for recovery and supported plist commands.')
    parser.add_argument('--prebuilt-jar', metavar='PATH', help='deploy an already verified Boot JAR without Maven')
    parser.add_argument('--with-tests', action='store_true', help='run tests in rebuild mode')
    args = parser.parse_args()
    if args.with_tests and args.prebuilt_jar:
        parser.error('--with-tests applies only to rebuild mode')
    if checked('uname', '-s').strip() != 'Darwin':
        raise DeployError('This script manages a macOS launchd service; use black-box.service on Linux')
    os.umask(0o077)
    def interrupted(signum, frame):
        raise DeployError(f'Deployment interrupted by signal {signum}')
    for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, interrupted)
    Deployment(args).execute()


if __name__ == '__main__':
    try:
        main()
    except (DeployError, OSError, ValueError, subprocess.TimeoutExpired, ET.ParseError, plistlib.InvalidFileException) as error:
        print(f'Deploy failed: {error}', file=sys.stderr)
        sys.exit(1)
