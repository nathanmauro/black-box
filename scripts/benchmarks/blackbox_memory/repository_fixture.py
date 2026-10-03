#!/usr/bin/env python3
"""Qualify one of three fixed reviewed Java fixtures offline; never execute arbitrary candidate code."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
FIXTURES = HERE / 'repository_fixtures'
BASELINE = '5d76086eeb0d423207e0f5560b3ae1aa1f9bebc8'
REFERENCE = 'd833fa96942a558cc7bc453b504656a2df41148f'
SOURCE = 'src/main/java/dev/nathan/sbaagentic/recording/internal/application/RedactionService.java'
GRADER = 'src/test/java/dev/nathan/sbaagentic/recording/RepositoryFixtureContractTest.java'
CLASS = 'dev.nathan.sbaagentic.recording.RepositoryFixtureContractTest'
EXPORT_PATHS = ('pom.xml', 'README.md', 'LICENSE', 'src/main/java', 'src/main/resources', 'src/test/java')
CHANGED_PATHS = frozenset((SOURCE, 'docs/operations.md',
    'docs/superpowers/plans/2026-10-03-structured-secret-redaction.md',
    'src/test/java/dev/nathan/sbaagentic/recording/StructuredRedactionTest.java',
    'src/test/java/dev/nathan/sbaagentic/recording/StructuredRedactionHttpTest.java'))
SETTINGS = (b'<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><offline>true</offline>'
            b'<interactiveMode>false</interactiveMode></settings>')
FIXTURE_SHA256 = '96f045b76bda691480286c532d4048ffa5cb13e03da106081f73c8629f081b2a'
FIXTURE_SPECS = {
    'structured-redaction': {
        'baseline': BASELINE, 'reference': REFERENCE, 'source': SOURCE,
        'grader': GRADER, 'class': CLASS, 'manifest': 'structured-redaction.json',
        'manifest_sha256': FIXTURE_SHA256, 'changed_paths': CHANGED_PATHS,
        'task_file': 'TASK.md', 'grader_file': 'RepositoryFixtureContractTest.java',
    },
    'summary-export': {
        'baseline': '9933ade549c37af5d784edff650f74705d55fa83',
        'reference': '16ce9f343706d1818f0b73e67043e6e84a1104e0',
        'source': 'src/main/java/dev/nathan/sbaagentic/summary/internal/application/SummaryExportService.java',
        'grader': 'src/test/java/dev/nathan/sbaagentic/summary/internal/application/SummaryExportFixtureContractTest.java',
        'class': 'dev.nathan.sbaagentic.summary.internal.application.SummaryExportFixtureContractTest',
        'manifest': 'summary-export.json', 'manifest_sha256': 'd462253622dedcdae6dece623db4574d850a0c7a569db1fbd14ebcf9e8c8084d',
        'changed_paths': frozenset((
            'src/main/java/dev/nathan/sbaagentic/summary/internal/application/SummaryExportService.java', 'docs/operations.md',
            'docs/superpowers/plans/2026-10-03-summary-export-safety.md',
            'src/test/java/dev/nathan/sbaagentic/summary/internal/application/SummaryExportSafetyTest.java')),
        'task_file': 'SUMMARY_EXPORT_TASK.md', 'grader_file': 'SummaryExportFixtureContractTest.java',
    },
    'event-chronology': {
        'baseline': 'a2f969585dc5b780b3dc4b0611a4084ec7efd0aa',
        'reference': 'aac7a30230687e795f844a971c72ebd5fd393e5c',
        'source': 'src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java',
        'sources': (
            'src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java',
            'src/main/java/dev/nathan/sbaagentic/memory/internal/adapter/out/sqlite/MemorySqlQueryAdapter.java',
            'src/main/java/dev/nathan/sbaagentic/query/SqlInstant.java',
        ),
        'added_sources': ('src/main/java/dev/nathan/sbaagentic/query/SqlInstant.java',),
        'grader': 'src/test/java/dev/nathan/sbaagentic/recording/ChronologyFixtureContractTest.java',
        'class': 'dev.nathan.sbaagentic.recording.ChronologyFixtureContractTest',
        'manifest': 'event-chronology.json',
        'manifest_sha256': '11dc2100b2d7ab9cf2e998dc8a73ba57148994e6cc1a9ea6b5297709a9cc79aa',
        'changed_paths': frozenset((
            'docs/agent-integration.md',
            'docs/operations.md',
            'docs/superpowers/plans/2026-10-03-canonical-time-ordering.md',
            'src/main/java/dev/nathan/sbaagentic/memory/internal/adapter/out/sqlite/MemorySqlQueryAdapter.java',
            'src/main/java/dev/nathan/sbaagentic/query/SqlInstant.java',
            'src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java',
            'src/test/java/dev/nathan/sbaagentic/postgres/PostgresBackendContractTest.java',
            'src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectTimelineQueryPlanTest.java',
            'src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectTrajectoryQueryPlanTest.java',
            'src/test/java/dev/nathan/sbaagentic/query/SqlInstantAssertions.java',
            'src/test/java/dev/nathan/sbaagentic/query/SqlInstantTest.java',
            'src/test/java/dev/nathan/sbaagentic/recording/CanonicalTimeHttpContract.java',
            'src/test/java/dev/nathan/sbaagentic/recording/CanonicalTimeHttpTest.java',
        )),
        'task_file': 'CHRONOLOGY_TASK.md',
        'grader_file': 'ChronologyFixtureContractTest.java',
    },
}
GATE = {
    'status': 'not_cleared', 'historical_candidates_required': 20, 'resumed_tasks_required': 5,
    'ordinary_latest_handoff_search_comparator': 'same model, budget and source window; not evaluated',
    'minimum_useful_supported_suggestions_percent': 70,
    'stale_or_duplicate_suggestions_percent_must_be_below': 10,
    'accepted_actions_missed_by_comparator_required': 3,
    'acceptance': 'must be observed; not established by grading',
    'existing_development_gate': '1–4 passes among five bare continuations; unchanged and not evaluated',
}


class FixtureError(Exception):
    pass


def sha(data):
    return hashlib.sha256(data).hexdigest()


def command(argv, *, cwd=None, env=None, timeout=30):
    """Only fixed trusted commands; terminate this invocation's process group on interruption."""
    if os.name != 'posix':
        raise FixtureError('posix_runtime_required')
    try:
        process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
    except OSError:
        raise FixtureError('tool_unavailable') from None
    try:
        out, err = process.communicate(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()
        raise FixtureError('process_timeout_or_interruption') from None
    return process.returncode, out, err


def git(*args):
    env = {'PATH': os.environ.get('PATH', ''), 'GIT_CONFIG_NOSYSTEM': '1',
           'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_NO_REPLACE_OBJECTS': '1'}
    code, out, _ = command(['git', '--no-replace-objects', '-C', str(REPO), *args], env=env)
    if code:
        raise FixtureError('pinned_git_evidence_unavailable')
    return out


def specification(name):
    if name not in FIXTURE_SPECS:
        raise FixtureError('unreviewed_fixture')
    return FIXTURE_SPECS[name]


def overlay_sources(spec):
    # Only fixed reviewed specs select overlays; retain the original single-source fixtures.
    return spec.get('sources') or (spec['source'],)


def fixture(name='structured-redaction'):
    spec = specification(name)
    raw = (FIXTURES / spec['manifest']).read_bytes()
    if sha(raw) != spec['manifest_sha256']:
        raise FixtureError('fixture_manifest_changed')
    data = json.loads(raw)
    if data['baseline'] != spec['baseline'] or data['reference'] != spec['reference']:
        raise FixtureError('unreviewed_revision')
    trusted_bytes(data)
    data['_fixture'] = name
    return data


def trusted_bytes(data):
    result = {}
    for name, digest in data['trusted_files'].items():
        path = FIXTURES / name
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except OSError:
            raise FixtureError('trusted_fixture_changed') from None
        try:
            if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                raise FixtureError('trusted_fixture_changed')
            with os.fdopen(descriptor, 'rb', closefd=False) as source:
                raw = source.read()
        finally:
            os.close(descriptor)
        if sha(raw) != digest:
            raise FixtureError('trusted_fixture_changed')
        result[name] = raw
    return result


def verify_pins(data):
    spec = specification(data['_fixture'])
    baseline, reference = spec['baseline'], spec['reference']
    for revision in (baseline, reference):
        if git('cat-file', '-t', revision).strip() != b'commit':
            raise FixtureError('pinned_commit_missing')
    if git('rev-parse', reference + '^').decode().strip() != baseline:
        raise FixtureError('reference_parent_changed')
    changed = frozenset(git('diff', '--no-ext-diff', '--no-textconv', '--name-only',
                            baseline, reference).decode().splitlines())
    if changed != spec['changed_paths']:
        raise FixtureError('reference_change_allowlist_mismatch')
    for path in spec.get('added_sources', ()):
        if git('ls-tree', '--name-only', baseline, '--', path).strip():
            raise FixtureError('baseline_added_source_already_exists')
    for revision in (baseline, reference):
        for path, expected in data['tracked_hashes'][revision].items():
            if sha(git('show', revision + ':' + path)) != expected:
                raise FixtureError('tracked_source_or_build_changed')


def allowed_export(name):
    path = PurePosixPath(name)
    return (not path.is_absolute() and '..' not in path.parts
            and any(name == p or name.startswith(p + '/') for p in EXPORT_PATHS))


def extract_snapshot(archive, destination):
    """Do not use tar.extract: tracked links and special files are refused, never followed."""
    total = 0
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source:
            name = member.name.rstrip('/')
            if member.isdir():
                continue
            if not member.isfile() or not allowed_export(name) or member.size > 10_000_000:
                raise FixtureError('unsafe_snapshot_entry')
            total += member.size
            if total > 100_000_000:
                raise FixtureError('snapshot_too_large')
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with target.open('xb') as output:
                os.chmod(target, 0o600)
                output.write(source.extractfile(member).read())


def hashes(root):
    result = {}
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if relative.parts[0] == 'target':
            continue
        if path.is_symlink():
            raise FixtureError('workspace_link_detected')
        if path.is_file():
            result[relative.as_posix()] = sha(path.read_bytes())
    return result


def build_environment(home, basedir):
    # No inherited SBA/SPRING, proxy, Maven/JVM option, provider or credential variables.
    env = {'PATH': os.environ.get('PATH', ''), 'HOME': str(home), 'TMPDIR': str(home),
           'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'MAVEN_OPTS': '-Duser.home=' + str(home), 'MAVEN_SKIP_RC': 'true',
           'MAVEN_BASEDIR': str(basedir)}
    if os.environ.get('JAVA_HOME'):
        env['JAVA_HOME'] = os.environ['JAVA_HOME']
    return env


def maven_recipe(root, settings, cache, home, data=None):
    name = CLASS if data is None else specification(data['_fixture'])['class']
    return ['mvn', '-o', '-B', '--no-transfer-progress', '-s', str(settings), '-gs', str(settings),
            '-Dmaven.repo.local=' + str(cache), '-Duser.home=' + str(home),
            '-Dtest=' + name, '-DfailIfNoTests=true', '-Dsurefire.failIfNoSpecifiedTests=true',
            '-DforkCount=1', '-DreuseForks=false', '-Dspring.main.web-application-type=none',
            '-Dsba.local-ai.enabled=false', '-Dsba.memory.embedding.enabled=false',
            '-Dsba.ask.embedding-enabled=false', '-Dsba.judge.enabled=false',
            '-Dsba.editor.enabled=false', '-Dsba.elasticsearch.enabled=false',
            '-Dsba.summary.backend=local', 'test']


def classify_reports(root, data, exit_code):
    name = specification(data['_fixture'])['class']
    reports = list((root / 'target/surefire-reports').glob('TEST-*.xml'))
    if len(reports) != 1:
        raise FixtureError('missing_or_unexpected_test_report')
    try:
        suite = ET.parse(reports[0]).getroot()
    except (ET.ParseError, OSError):
        raise FixtureError('invalid_test_report') from None
    cases = suite.findall('testcase')
    if any(error.get('message') == 'fixture_filesystem_unavailable'
           for case in cases for error in case.findall('error')):
        raise FixtureError('fixture_filesystem_unavailable')
    names = [case.get('name') for case in cases]
    expected = data['tests']
    if (suite.get('name') != name or len(names) != len(expected) or set(names) != set(expected)
            or any(case.get('classname') != name for case in cases)):
        raise FixtureError('test_inventory_mismatch')
    if any(case.find('skipped') is not None for case in cases) or int(suite.get('skipped', '0')):
        raise FixtureError('skipped_tests')
    if any(case.find('error') is not None for case in cases) or int(suite.get('errors', '0')):
        raise FixtureError('test_execution_error')
    failed = []
    for case in cases:
        failures = case.findall('failure')
        if failures:
            if len(failures) != 1 or failures[0].get('type') != 'org.opentest4j.AssertionFailedError':
                raise FixtureError('non_behavioral_test_failure')
            failed.append(case.get('name'))
    if int(suite.get('tests', '-1')) != len(expected) or int(suite.get('failures', '-1')) != len(failed):
        raise FixtureError('inconsistent_test_report')
    if (exit_code == 0) != (not failed):
        raise FixtureError('inconsistent_build_exit')
    return {'tests': len(cases), 'failed': sorted(failed), 'passed': len(cases) - len(failed)}


def run_stage(root, data, settings, cache, home, timeout, expected):
    fixture(data['_fixture'])
    if hashes(root) != expected or settings.read_bytes() != SETTINGS:
        raise FixtureError('grading_inputs_changed')
    started = time.monotonic()
    code, out, err = command(maven_recipe(root, settings, cache, home, data), cwd=root,
                            env=build_environment(home, root), timeout=timeout)
    if hashes(root) != expected or settings.read_bytes() != SETTINGS:
        raise FixtureError('grading_inputs_changed')
    fixture(data['_fixture'])  # Controller-owned grading files must also stay unchanged during execution.
    if not (root / 'target/surefire-reports').is_dir():
        text = (out + err).decode(errors='replace')
        if 'Cannot access' in text and 'offline mode' in text or 'has not been downloaded' in text:
            raise FixtureError('offline_dependency_unavailable')
        if 'COMPILATION ERROR' in text or 'Compilation failure' in text:
            raise FixtureError('compilation_failed')
        raise FixtureError('build_failed_before_tests')
    result = classify_reports(root, data, code)
    result['seconds'] = round(time.monotonic() - started, 3)
    return result


def qualify(data, cache, timeout):
    spec = specification(data['_fixture'])
    if not cache.is_dir():
        raise FixtureError('maven_cache_missing')
    trusted = trusted_bytes(data)
    archive = git('archive', '--format=tar', spec['baseline'], '--', *EXPORT_PATHS)
    with tempfile.TemporaryDirectory(prefix='blackbox-repository-fixture-') as temp:
        private = Path(temp).resolve()
        home = private / 'home'
        home.mkdir(mode=0o700)
        settings = home / 'settings.xml'
        settings.write_bytes(SETTINGS)
        (home / '.mvn').mkdir(mode=0o700)
        env = build_environment(home, home)
        code, out, err = command(['mvn', '-o', '-B', '-s', str(settings), '-gs', str(settings), '-version'], cwd=home, env=env)
        if code or not re.search(rb'Java version: 21(?:[.,\s])', out + err):
            raise FixtureError('java21_maven_required')
        worker = private / 'worker-input'
        worker.mkdir(mode=0o700)
        extract_snapshot(archive, worker)
        (worker / 'TASK.md').write_bytes(trusted[spec['task_file']])
        worker_hashes = hashes(worker)
        results = {}
        for name, revision in (('baseline', spec['baseline']), ('reference', spec['reference'])):
            grading = private / ('grading-' + name)
            shutil.copytree(worker, grading)
            target = grading / spec['grader']
            if target.exists():
                raise FixtureError('grader_path_already_exists')
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            target.write_bytes(trusted[spec['grader_file']])
            (grading / '.mvn').mkdir(mode=0o700)
            if name == 'reference':
                for path in overlay_sources(spec):
                    target = grading / path
                    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    target.write_bytes(git('show', spec['reference'] + ':' + path))
            expected = dict(worker_hashes, **{spec['grader']: data['trusted_files'][spec['grader_file']]})
            expected.update(data['tracked_hashes'][revision])
            results[name] = run_stage(grading, data, settings, cache, home, timeout, expected)
            required = sorted(data['baseline_failures']) if name == 'baseline' else []
            if results[name]['failed'] != required:
                raise FixtureError('baseline_not_reproduced' if name == 'baseline' else 'reference_not_correct')
            if hashes(worker) != worker_hashes:
                raise FixtureError('worker_export_changed')
        return {'qualification': 'passed', 'results': results, 'worker_input_sha256': sha(
                    json.dumps(worker_hashes, sort_keys=True).encode()), 'baseline_archive_sha256': sha(archive)}


def output_path(value):
    path = Path(value).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)) or path.exists() or not path.parent.is_dir():
        raise FixtureError('output_must_be_new_without_symlinks')
    return path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', nargs='?', default='plan', choices=('plan', 'verify'))
    parser.add_argument('--fixture', choices=tuple(FIXTURE_SPECS), default='structured-redaction')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--output', help='Optional new JSON report; no source/log artifacts are retained')
    parser.add_argument('--maven-repo', type=Path, default=Path.home() / '.m2/repository')
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args(argv)
    spec = specification(args.fixture)
    report = {'schema_version': 1, 'evidence': 'infrastructure_only', 'fixture': args.fixture + '-development',
              'baseline': spec['baseline'], 'reference': spec['reference'], 'usefulness_gate': GATE,
              'model_runs': 0, 'accepted_actions': 0, 'qualification': 'failed'}
    destination = None
    status = 2
    try:
        data = fixture(args.fixture)
        verify_pins(data)
        if args.timeout <= 0:
            raise FixtureError('positive_timeout_required')
        if args.mode == 'plan':
            report['qualification'] = 'planned'
        else:
            if not args.execute:
                raise FixtureError('execute_flag_required')
            if args.output:
                destination = output_path(args.output)
            report.update(qualify(data, args.maven_repo.resolve(), args.timeout))
        status = 0
    except FixtureError as exc:
        report['error'] = str(exc)
    except (OSError, ValueError, KeyError, tarfile.TarError):
        report['error'] = 'fixture_input_or_io_failure'
    encoded = json.dumps(report, indent=2, sort_keys=True) + '\n'
    if destination:
        try:
            with open(destination, 'x', encoding='utf-8', opener=lambda p, f: os.open(p, f, 0o600)) as out:
                out.write(encoded)
        except OSError:
            report['error'] = 'report_write_failed'
            report['qualification'] = 'failed'
            encoded = json.dumps(report, indent=2, sort_keys=True) + '\n'
            status = 2
    print(encoded, end='')
    return status


if __name__ == '__main__':
    sys.exit(main())
