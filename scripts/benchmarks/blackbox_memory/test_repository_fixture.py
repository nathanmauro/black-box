import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import repository_fixture as r


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        for name, content, kind in entries:
            item = tarfile.TarInfo(name)
            item.type = kind
            item.size = len(content) if kind == tarfile.REGTYPE else 0
            item.linkname = '/outside'
            tar.addfile(item, io.BytesIO(content))
    return output.getvalue()


class RepositoryFixtureTests(unittest.TestCase):
    fixture_name = 'structured-redaction'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.data = r.fixture(self.fixture_name)
        self.spec = r.specification(self.fixture_name)
        (self.root / 'settings').write_bytes(r.SETTINGS)

    def main(self, arguments):
        return r.main(arguments + ['--fixture', self.fixture_name])

    def fake_snapshot(self, data):
        sources = r.overlay_sources(self.spec)
        added = self.spec.get('added_sources', ())
        baseline = [path for path in sources if path not in added]
        for rev, content, paths in ((self.spec['baseline'], b'baseline', baseline),
                                    (self.spec['reference'], b'reference', sources)):
            data['tracked_hashes'][rev] = dict({'pom.xml': r.sha(b'trusted')},
                                             **{path: r.sha(content) for path in paths})
        return archive([('pom.xml', b'trusted', tarfile.REGTYPE), ('README.md', b'public', tarfile.REGTYPE)]
                       + [(path, b'baseline', tarfile.REGTYPE) for path in baseline])

    def reports(self, failed=(), skipped=(), errors=()):
        folder = self.root / 'target/surefire-reports'
        folder.mkdir(parents=True, exist_ok=True)
        suite = ET.Element('testsuite', name=self.spec['class'], tests=str(len(self.data['tests'])),
                           failures=str(len(failed)), skipped=str(len(skipped)), errors=str(len(errors)))
        for name in self.data['tests']:
            case = ET.SubElement(suite, 'testcase', name=name, classname=self.spec['class'])
            if name in failed:
                ET.SubElement(case, 'failure', type='org.opentest4j.AssertionFailedError')
            if name in skipped:
                ET.SubElement(case, 'skipped')
            if name in errors:
                ET.SubElement(case, 'error', type='java.lang.IllegalStateException')
        path = folder / ('TEST-' + self.spec['class'] + '.xml')
        ET.ElementTree(suite).write(path)
        return path

    def test_fixture_pins_and_public_development_status(self):
        self.assertEqual(self.data['baseline'], self.spec['baseline'])
        self.assertEqual(self.data['reference'], self.spec['reference'])
        self.assertTrue(self.data['development_only'])
        self.assertEqual(self.data['public_repository'], 'https://github.com/nathanmauro/black-box')
        expected = {'structured-redaction': 6, 'capture-ack': 8}.get(self.fixture_name, 7)
        self.assertEqual(len(self.data['tests']), expected)

    def test_changed_manifest_and_grader_fail_closed(self):
        copy_root = self.root / 'fixtures'
        shutil.copytree(r.FIXTURES, copy_root)
        with patch.object(r, 'FIXTURES', copy_root):
            (copy_root / self.spec['grader_file']).write_text('changed')
            with self.assertRaisesRegex(r.FixtureError, 'trusted_fixture_changed'):
                r.fixture(self.fixture_name)
            (copy_root / self.spec['manifest']).write_text('{}')
            with self.assertRaisesRegex(r.FixtureError, 'fixture_manifest_changed'):
                r.fixture(self.fixture_name)

    def test_linked_or_non_regular_trusted_inputs_are_never_read(self):
        copied = self.root / 'trusted'
        shutil.copytree(r.FIXTURES, copied)
        grader = copied / next(iter(self.data['trusted_files']))
        external = self.root / 'external-fixture-only'
        external.write_text('must not be read')
        grader.unlink()
        grader.symlink_to(external)
        with patch.object(r, 'FIXTURES', copied), patch.object(r.os, 'fdopen') as read, \
                patch.object(Path, 'read_bytes', side_effect=AssertionError('unchecked file read')):
            with self.assertRaisesRegex(r.FixtureError, 'trusted_fixture_changed'):
                r.trusted_bytes(self.data)
            read.assert_not_called()
            grader.unlink()
            os.mkfifo(grader)
            with self.assertRaisesRegex(r.FixtureError, 'trusted_fixture_changed'):
                r.trusted_bytes(self.data)
            read.assert_not_called()

    def test_pins_reject_extra_reference_paths_and_wrong_build_bytes(self):
        data = copy.deepcopy(self.data)
        data['tracked_hashes'] = {rev: {'pom.xml': r.sha(b'reviewed')} for rev in (self.spec['baseline'], self.spec['reference'])}
        def git(*args):
            if args[0] == 'cat-file': return b'commit\n'
            if args[0] == 'rev-parse': return self.spec['baseline'].encode() + b'\n'
            if args[0] == 'diff': return '\n'.join(sorted(self.spec['changed_paths'])).encode()
            if args[0] == 'ls-tree': return b''
            return b'reviewed'
        with patch.object(r, 'git', side_effect=git):
            r.verify_pins(data)
        with patch.object(r, 'git', side_effect=lambda *args: b'changed' if args[0] == 'show' else git(*args)):
            with self.assertRaisesRegex(r.FixtureError, 'tracked_source_or_build_changed'):
                r.verify_pins(data)
        with patch.object(r, 'git', side_effect=lambda *args: git(*args) + b'\npom.xml' if args[0] == 'diff' else git(*args)):
            with self.assertRaisesRegex(r.FixtureError, 'reference_change_allowlist_mismatch'):
                r.verify_pins(data)

    def test_snapshot_excludes_history_metadata_and_rejects_links_traversal_duplicates(self):
        for name, kind in [('.git/config', tarfile.REGTYPE), ('docs/post-fix.md', tarfile.REGTYPE),
                           ('../pom.xml', tarfile.REGTYPE), ('/pom.xml', tarfile.REGTYPE),
                           ('src/main/java/link', tarfile.SYMTYPE), ('pom.xml', tarfile.LNKTYPE)]:
            with self.subTest(name=name, kind=kind), self.assertRaisesRegex(r.FixtureError, 'unsafe_snapshot_entry'):
                r.extract_snapshot(archive([(name, b'x', kind)]), self.root)
        safe = archive([('pom.xml', b'trusted', tarfile.REGTYPE)])
        r.extract_snapshot(safe, self.root)
        self.assertEqual((self.root / 'pom.xml').read_bytes(), b'trusted')
        with self.assertRaises(FileExistsError):
            r.extract_snapshot(safe, self.root)

    def test_strict_behavioral_failure_and_success_grades(self):
        self.reports(failed=self.data['baseline_failures'])
        result = r.classify_reports(self.root, self.data, 1)
        self.assertEqual(result['failed'], sorted(self.data['baseline_failures']))
        self.reports()
        self.assertEqual(r.classify_reports(self.root, self.data, 0)['passed'], len(self.data['tests']))
        with self.assertRaisesRegex(r.FixtureError, 'inconsistent_build_exit'):
            r.classify_reports(self.root, self.data, 1)

    def test_skipped_errors_zero_unexpected_duplicate_and_incomplete_reports_are_refused(self):
        for mode in ('skip', 'error', 'zero', 'unexpected', 'duplicate', 'inconsistent', 'foreign_failure'):
            with self.subTest(mode=mode):
                path = self.reports(skipped=self.data['tests'][:1] if mode == 'skip' else (),
                                    errors=self.data['tests'][:1] if mode == 'error' else ())
                tree = ET.parse(path)
                suite = tree.getroot()
                if mode == 'zero':
                    for case in list(suite): suite.remove(case)
                    suite.set('tests', '0')
                if mode == 'unexpected': suite[0].set('name', 'unreviewed')
                if mode == 'duplicate': suite[0].set('name', suite[1].get('name'))
                if mode == 'inconsistent': suite.set('failures', '1')
                if mode == 'foreign_failure':
                    ET.SubElement(suite[0], 'failure', type='java.lang.LinkageError')
                tree.write(path)
                with self.assertRaises(r.FixtureError): r.classify_reports(self.root, self.data, 0)
        self.reports()
        (self.root / 'target/surefire-reports/TEST-extra.xml').write_text('<testsuite/>')
        with self.assertRaisesRegex(r.FixtureError, 'missing_or_unexpected_test_report'):
            r.classify_reports(self.root, self.data, 0)

    def test_environment_and_recipe_do_not_inherit_auth_settings_or_online_options(self):
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'fixture', 'SPRING_APPLICATION_JSON': 'fixture',
                                     'MAVEN_ARGS': '-DskipTests', 'JAVA_TOOL_OPTIONS': 'fixture'}):
            env = r.build_environment(self.root, self.root)
        for forbidden in ('OPENAI_API_KEY', 'SPRING_APPLICATION_JSON', 'MAVEN_ARGS', 'JAVA_TOOL_OPTIONS'):
            self.assertNotIn(forbidden, env)
        self.assertEqual(env['MAVEN_SKIP_RC'], 'true')
        command = r.maven_recipe(self.root, self.root / 'settings', self.root / 'cache', self.root, self.data)
        self.assertIn('-o', command)
        self.assertIn('-gs', command)
        self.assertIn('-s', command)
        self.assertIn('-Dtest=' + self.spec['class'], command)
        self.assertNotIn('spring-boot:run', command)

    def test_missing_dependencies_and_compile_failure_are_not_behavioral_failures(self):
        for output, category in [(b'Cannot access repo in offline mode', 'offline_dependency_unavailable'),
                                 (b'COMPILATION ERROR', 'compilation_failed'), (b'other', 'build_failed_before_tests')]:
            with self.subTest(category=category), patch.object(r, 'command', return_value=(1, output, b'')):
                with self.assertRaisesRegex(r.FixtureError, category):
                    r.run_stage(self.root, self.data, self.root / 'settings', self.root, self.root, 1, r.hashes(self.root))

    def test_changed_grading_inputs_are_refused(self):
        tracked = self.root / 'pom.xml'
        tracked.write_text('trusted')
        def changed(*args, **kwargs):
            tracked.write_text('changed')
            return 0, b'', b''
        with patch.object(r, 'command', side_effect=changed), self.assertRaisesRegex(r.FixtureError, 'grading_inputs_changed'):
            r.run_stage(self.root, self.data, self.root / 'settings', self.root, self.root, 1, r.hashes(self.root))

    def test_timeout_terminates_owned_process(self):
        real_popen = subprocess.Popen
        created = []
        def capture(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            created.append(process)
            return process
        with patch.object(r.subprocess, 'Popen', side_effect=capture):
            with self.assertRaisesRegex(r.FixtureError, 'process_timeout_or_interruption'):
                r.command([sys.executable, '-c', 'import time; time.sleep(30)'], timeout=0.05)
        self.assertIsNotNone(created[0].poll())

    def test_private_staging_separates_worker_reference_and_grader_then_cleans(self):
        data = copy.deepcopy(self.data)
        snapshot = self.fake_snapshot(data)
        seen = []
        def stage(root, data, *args):
            seen.append(root)
            expected = args[-1]
            self.assertEqual(r.hashes(root), expected)
            worker = root.parent / 'worker-input'
            self.assertFalse((worker / self.spec['grader']).exists())
            self.assertEqual((worker / self.spec['source']).read_bytes(), b'baseline')
            self.assertFalse((worker / '.git').exists())
            self.assertTrue((root / self.spec['grader']).is_file())
            baseline = root.name.endswith('baseline')
            for source in r.overlay_sources(self.spec):
                if source in self.spec.get('added_sources', ()):
                    self.assertFalse((worker / source).exists())
                    if baseline:
                        self.assertFalse((root / source).exists())
                        continue
                self.assertEqual((root / source).read_bytes(), b'baseline' if baseline else b'reference')
            return {'tests': len(data['tests']), 'passed': len(data['tests']) - 3 if baseline else len(data['tests']),
                    'failed': sorted(data['baseline_failures']) if baseline else []}
        with patch.object(r, 'git', side_effect=lambda *args: snapshot if args[0] == 'archive' else b'reference'), \
                patch.object(r, 'command', return_value=(0, b'Java version: 21.0.12', b'')), \
                patch.object(r, 'run_stage', side_effect=stage):
            self.assertEqual(r.qualify(data, self.root, 1)['qualification'], 'passed')
        self.assertTrue(seen)
        self.assertTrue(all(not path.exists() for path in seen))

    def test_mutating_grader_after_initial_load_never_runs_maven(self):
        copied = self.root / 'trusted'
        shutil.copytree(r.FIXTURES, copied)
        with patch.object(r, 'FIXTURES', copied):
            data = r.fixture(self.fixture_name)
            (copied / self.spec['grader_file']).write_text('unreviewed replacement')
            with patch.object(r, 'command') as execute, self.assertRaisesRegex(r.FixtureError, 'trusted_fixture_changed'):
                r.qualify(data, self.root, 1)
            execute.assert_not_called()

    def test_staged_extra_or_changed_files_prevent_build_launch(self):
        expected = r.hashes(self.root)
        (self.root / 'unexpected.java').write_text('unreviewed')
        with patch.object(r, 'command') as execute, self.assertRaisesRegex(r.FixtureError, 'grading_inputs_changed'):
            r.run_stage(self.root, self.data, self.root / 'settings', self.root, self.root, 1, expected)
        execute.assert_not_called()

    def test_all_maven_launches_ignore_contaminated_caller_and_ancestor_config(self):
        binary = self.root / 'bin'
        binary.mkdir()
        launcher = binary / 'mvn'
        launcher.write_text('#!' + sys.executable + '\n' + """
import os, sys
from pathlib import Path
import xml.etree.ElementTree as ET
cwd = Path.cwd()
base = Path(os.environ.get('MAVEN_BASEDIR', cwd))
assert base == cwd and (base / '.mvn').is_dir(), 'unanchored Maven cwd/base'
assert not (base / '.mvn/jvm.config').exists(), 'foreign JVM config'
assert os.environ.get('MAVEN_SKIP_RC') == 'true', 'rc files enabled'
assert '-s' in sys.argv and '-gs' in sys.argv and '-o' in sys.argv, 'ambient settings enabled'
if '-version' in sys.argv:
    print('Java version: 21.0.12')
    sys.exit(0)
names = NAMES
failed = FAILURES if cwd.name == 'grading-baseline' else []
suite = ET.Element('testsuite', name=CLASS, tests=str(len(names)), failures=str(len(failed)), errors='0', skipped='0')
for name in names:
    case = ET.SubElement(suite, 'testcase', name=name, classname=CLASS)
    if name in failed: ET.SubElement(case, 'failure', type='org.opentest4j.AssertionFailedError')
reports = cwd / 'target/surefire-reports'
reports.mkdir(parents=True)
ET.ElementTree(suite).write(reports / ('TEST-' + CLASS + '.xml'))
sys.exit(1 if failed else 0)
""".replace('NAMES', repr(self.data['tests'])).replace('FAILURES', repr(self.data['baseline_failures']))
                .replace('CLASS', repr(self.spec['class'])))
        launcher.chmod(0o700)
        caller = self.root / 'contaminated/caller'
        caller.mkdir(parents=True)
        for parent in (caller, caller.parent):
            (parent / '.mvn').mkdir()
            (parent / '.mvn/jvm.config').write_text('-XX:InvalidFixtureOption')
            (parent / '.mvn/maven.config').write_text('-DskipTests')
        data = copy.deepcopy(self.data)
        snapshot = self.fake_snapshot(data)
        original = Path.cwd()
        try:
            os.chdir(caller)
            with patch.dict(os.environ, {'PATH': str(binary) + os.pathsep + os.environ['PATH'],
                                         'MAVEN_BASEDIR': str(caller), 'MAVEN_ARGS': '-DskipTests'}), \
                    patch.object(r, 'git', side_effect=lambda *args: snapshot if args[0] == 'archive' else b'reference'):
                self.assertEqual(r.qualify(data, self.root, 2)['qualification'], 'passed')
        finally:
            os.chdir(original)

    def test_plan_has_no_build_or_output_and_verify_requires_explicit_execution(self):
        output = self.root / 'result.json'
        with patch.object(r, 'verify_pins'), patch.object(r, 'qualify') as build:
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(self.main(['plan', '--output', str(output)]), 0)
            self.assertEqual(json.loads(printed.getvalue())['qualification'], 'planned')
            self.assertFalse(output.exists())
            build.assert_not_called()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(self.main(['verify']), 2)
            build.assert_not_called()

    def test_qualified_fixture_never_clears_usefulness_gate(self):
        with patch.object(r, 'verify_pins'), patch.object(r, 'qualify', return_value={'qualification': 'passed'}):
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(self.main(['verify', '--execute']), 0)
        result = json.loads(printed.getvalue())
        self.assertEqual(result['evidence'], 'infrastructure_only')
        self.assertEqual(result['usefulness_gate']['status'], 'not_cleared')
        self.assertEqual(result['usefulness_gate']['historical_candidates_required'], 20)
        self.assertEqual(result['usefulness_gate']['accepted_actions_missed_by_comparator_required'], 3)
        self.assertEqual(result['model_runs'], 0)
        self.assertEqual(result['accepted_actions'], 0)

    def test_existing_and_linked_outputs_are_refused(self):
        target = self.root / 'existing'
        target.write_text('preserved')
        link = self.root / 'link'
        link.symlink_to(self.root, target_is_directory=True)
        for path in (target, link / 'new'):
            with self.assertRaises(r.FixtureError): r.output_path(str(path))
        self.assertEqual(target.read_text(), 'preserved')


    def test_only_five_known_selectors_and_original_default_are_allowed(self):
        self.assertEqual(set(r.FIXTURE_SPECS),
                         {'structured-redaction', 'summary-export', 'event-chronology', 'capture-ack', 'journal-race'})
        with patch.object(r, 'verify_pins'), patch.object(r, 'qualify') as build:
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(r.main(['plan']), 0)
            self.assertEqual(json.loads(printed.getvalue())['fixture'], 'structured-redaction-development')
            for unknown in ('../summary-export', 'HEAD', self.spec['reference']):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    r.main(['plan', '--fixture', unknown])
                self.assertEqual(error.exception.code, 2)
                with self.assertRaisesRegex(r.FixtureError, 'unreviewed_fixture'):
                    r.fixture(unknown)
            build.assert_not_called()

    def test_filesystem_prerequisite_error_cannot_count_as_behavior(self):
        path = self.reports(errors=self.data['tests'][:1])
        tree = ET.parse(path)
        tree.getroot()[0].find('error').set('message', 'fixture_filesystem_unavailable')
        tree.write(path)
        with self.assertRaisesRegex(r.FixtureError, 'fixture_filesystem_unavailable'):
            r.classify_reports(self.root, self.data, 1)

    def test_selected_fixture_revalidation_and_failure_cleanup(self):
        copied = self.root / 'reviewed'
        shutil.copytree(r.FIXTURES, copied)
        original_manifest = (copied / self.spec['manifest']).read_bytes()
        original_grader = (copied / self.spec['grader_file']).read_bytes()
        for failure in ('timeout', 'manifest', 'grader'):
            with self.subTest(failure=failure), patch.object(r, 'FIXTURES', copied):
                (copied / self.spec['manifest']).write_bytes(original_manifest)
                (copied / self.spec['grader_file']).write_bytes(original_grader)
                data = r.fixture(self.fixture_name)
                snapshot = self.fake_snapshot(data)
                stages = []
                def command(argv, **kwargs):
                    if '-version' in argv:
                        return 0, b'Java version: 21.0.12', b''
                    stages.append(kwargs['cwd'])
                    if failure == 'timeout':
                        raise r.FixtureError('process_timeout_or_interruption')
                    target = self.spec['manifest'] if failure == 'manifest' else self.spec['grader_file']
                    (copied / target).write_text('changed during selected build')
                    return 0, b'', b''
                expected = {'timeout': 'process_timeout_or_interruption', 'manifest': 'fixture_manifest_changed',
                            'grader': 'trusted_fixture_changed'}[failure]
                with patch.object(r, 'git', side_effect=lambda *args: snapshot if args[0] == 'archive' else b'reference'), \
                        patch.object(r, 'command', side_effect=command), self.assertRaisesRegex(r.FixtureError, expected):
                    r.qualify(data, self.root, 1)
                self.assertEqual(len(stages), 1)
                self.assertTrue(all(not path.parent.exists() for path in stages))


class SummaryExportRepositoryFixtureTests(RepositoryFixtureTests):
    fixture_name = 'summary-export'


class ChronologyRepositoryFixtureTests(RepositoryFixtureTests):
    fixture_name = 'event-chronology'

    def test_reviewed_three_source_closure_and_added_helper_are_pinned(self):
        sources = r.overlay_sources(self.spec)
        self.assertEqual(len(sources), 3)
        self.assertEqual(len(self.spec['changed_paths']), 13)
        self.assertEqual(self.spec['added_sources'], ('src/main/java/dev/nathan/sbaagentic/query/SqlInstant.java',))
        for source in sources:
            self.assertIn(source, self.data['tracked_hashes'][self.spec['reference']])
        self.assertNotIn(self.spec['added_sources'][0], self.data['tracked_hashes'][self.spec['baseline']])
        for path in ('pom.xml', 'src/main/resources/schema.sql', 'src/main/resources/application.yml'):
            self.assertEqual(self.data['tracked_hashes'][self.spec['baseline']][path],
                             self.data['tracked_hashes'][self.spec['reference']][path])
        def git(*args):
            if args[0] == 'cat-file': return b'commit'
            if args[0] == 'rev-parse': return self.spec['baseline'].encode()
            if args[0] == 'diff': return '\n'.join(self.spec['changed_paths']).encode()
            if args[0] == 'ls-tree': return self.spec['added_sources'][0].encode()
            raise AssertionError('hash checks should not run after absent-source mismatch')
        with patch.object(r, 'git', side_effect=git), self.assertRaisesRegex(r.FixtureError, 'baseline_added_source_already_exists'):
            r.verify_pins(self.data)

    def test_each_changed_reference_source_is_checked_before_build_and_private_tree_is_removed(self):
        for changed in r.overlay_sources(self.spec):
            with self.subTest(changed=changed):
                data = copy.deepcopy(self.data)
                snapshot = self.fake_snapshot(data)
                roots = []
                original_stage = r.run_stage
                def stage(root, data, *args):
                    roots.append(root)
                    if root.name == 'grading-baseline':
                        self.assertEqual(r.hashes(root), args[-1])
                        return {'tests': 7, 'passed': 4, 'failed': sorted(data['baseline_failures'])}
                    return original_stage(root, data, *args)
                def git(*args):
                    if args[0] == 'archive': return snapshot
                    return b'tampered' if args[1].endswith(':' + changed) else b'reference'
                with patch.object(r, 'git', side_effect=git), patch.object(r, 'run_stage', side_effect=stage), \
                        patch.object(r, 'command', return_value=(0, b'Java version: 21.0.12', b'')) as launch:
                    with self.assertRaisesRegex(r.FixtureError, 'grading_inputs_changed'):
                        r.qualify(data, self.root, 1)
                    self.assertEqual(launch.call_count, 1)  # Version preflight only; no build.
                self.assertEqual(len(roots), 2)
                self.assertTrue(all(not root.parent.exists() for root in roots))

    def test_original_manifest_and_trusted_file_bytes_remain_pinned(self):
        self.assertEqual(r.sha((r.FIXTURES / 'structured-redaction.json').read_bytes()),
                         '96f045b76bda691480286c532d4048ffa5cb13e03da106081f73c8629f081b2a')
        self.assertEqual(r.sha((r.FIXTURES / 'summary-export.json').read_bytes()),
                         'd462253622dedcdae6dece623db4574d850a0c7a569db1fbd14ebcf9e8c8084d')
        for name in ('structured-redaction', 'summary-export'):
            self.assertEqual(r.overlay_sources(r.specification(name)), (r.specification(name)['source'],))
            r.fixture(name)


class CaptureAckRepositoryFixtureTests(RepositoryFixtureTests):
    fixture_name = 'capture-ack'
    SERVICE = 'src/main/java/dev/nathan/sbaagentic/recording/internal/application/EventIngestService.java'
    STORE = 'src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java'

    def test_single_service_overlay_and_exact_reference_delta_are_pinned(self):
        self.assertEqual(r.overlay_sources(self.spec), (self.SERVICE,))
        self.assertNotIn('added_sources', self.spec)
        self.assertEqual(self.spec['changed_paths'], frozenset((
            'docs/agent-integration.md',
            'docs/superpowers/plans/2026-10-03-capture-acknowledgement.md',
            self.SERVICE,
            'src/test/java/dev/nathan/sbaagentic/recording/CaptureAcknowledgementHttpMcpTest.java')))
        baseline = self.data['tracked_hashes'][self.spec['baseline']]
        reference = self.data['tracked_hashes'][self.spec['reference']]
        self.assertNotEqual(baseline[self.SERVICE], reference[self.SERVICE])
        # The real store and its transaction annotations are shared, so the grader sees one boundary.
        for path in ('pom.xml', 'src/main/resources/schema.sql', 'src/main/resources/application.yml', self.STORE):
            self.assertEqual(baseline[path], reference[path])
        self.assertEqual(set(baseline), set(reference))

    def test_reference_test_and_docs_are_never_overlaid(self):
        data = copy.deepcopy(self.data)
        snapshot = self.fake_snapshot(data)
        shown = []
        def git(*args):
            if args[0] == 'archive': return snapshot
            shown.append(args[1].split(':', 1)[1])
            return b'reference'
        def stage(root, data, *args):
            if root.name == 'grading-reference':
                self.assertFalse((root / 'src/test/java/dev/nathan/sbaagentic/recording/'
                                  'CaptureAcknowledgementHttpMcpTest.java').exists())
                self.assertFalse((root / 'docs').exists())
                self.assertEqual((root / self.SERVICE).read_bytes(), b'reference')
                return {'tests': 8, 'passed': 8, 'failed': []}
            return {'tests': 8, 'passed': 4, 'failed': sorted(data['baseline_failures'])}
        with patch.object(r, 'git', side_effect=git), patch.object(r, 'run_stage', side_effect=stage), \
                patch.object(r, 'command', return_value=(0, b'Java version: 21.0.12', b'')):
            self.assertEqual(r.qualify(data, self.root, 1)['qualification'], 'passed')
        self.assertEqual(shown, [self.SERVICE])

    def test_baseline_must_fail_exactly_the_four_named_acknowledgement_checks(self):
        self.assertEqual(sorted(self.data['baseline_failures']), sorted((
            'optionalRecordedFailureAcknowledgesCommittedCapture',
            'terminalStopIsAttemptedAfterRecordedFailure',
            'terminalStopFailureAcknowledgesCommittedCapture',
            'bothTerminalFailuresAcknowledgeAfterBothAttempts')))
        self.assertEqual(len(set(self.data['tests']) - set(self.data['baseline_failures'])), 4)
        data = copy.deepcopy(self.data)
        snapshot = self.fake_snapshot(data)
        for failed in (self.data['baseline_failures'][:3], self.data['tests']):
            with self.subTest(failed=len(failed)):
                def stage(root, data, *args, failed=failed):
                    return {'tests': 8, 'passed': 8 - len(failed), 'failed': sorted(failed)}
                with patch.object(r, 'git', side_effect=lambda *a: snapshot if a[0] == 'archive' else b'reference'), \
                        patch.object(r, 'run_stage', side_effect=stage), \
                        patch.object(r, 'command', return_value=(0, b'Java version: 21.0.12', b'')), \
                        self.assertRaisesRegex(r.FixtureError, 'baseline_not_reproduced'):
                    r.qualify(data, self.root, 1)

    def test_prior_fixture_manifests_remain_pinned(self):
        for name, digest in (
                ('structured-redaction.json', '96f045b76bda691480286c532d4048ffa5cb13e03da106081f73c8629f081b2a'),
                ('summary-export.json', 'd462253622dedcdae6dece623db4574d850a0c7a569db1fbd14ebcf9e8c8084d'),
                ('event-chronology.json', '11dc2100b2d7ab9cf2e998dc8a73ba57148994e6cc1a9ea6b5297709a9cc79aa')):
            self.assertEqual(r.sha((r.FIXTURES / name).read_bytes()), digest)
        for name in ('structured-redaction', 'summary-export', 'event-chronology'):
            r.fixture(name)


class JournalRaceRepositoryFixtureTests(unittest.TestCase):
    """The single Python fixture: fixed isolated recipe, exact export, strict JSON report."""
    SOURCE = 'scripts/hooks/capture_outbox.py'
    EXPORT = ('LICENSE', 'README.md', 'scripts/hooks/capture_outbox.py',
              'scripts/hooks/fixtures/capture-redaction-v1.json', 'scripts/hooks/sba-agent-hook.sh',
              'scripts/hooks/test_capture_outbox.py')
    RUNTIME = {'python': '3.9.6', 'sqlite': '3.54.0', 'platform': 'Darwin-arm64'}

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.data = r.fixture('journal-race')
        self.spec = r.specification('journal-race')

    def snapshot(self, data, entries=None):
        """A fake baseline export; tracked hashes follow its fake bytes."""
        entries = list(self.EXPORT) if entries is None else entries
        baseline = data['tracked_hashes'][self.spec['baseline']]
        reference = data['tracked_hashes'][self.spec['reference']]
        for path in list(baseline):
            baseline[path] = r.sha(b'baseline:' + path.encode())
        for path in list(reference):
            reference[path] = r.sha(b'reference' if path == self.SOURCE else b'baseline:' + path.encode())
        return archive([(path, b'baseline:' + path.encode(), tarfile.REGTYPE) for path in entries])

    def report(self, root, failed=(), outcome='failure', kind='AssertionError', **changes):
        cases = [{'name': name, 'outcome': outcome if name in failed else 'pass',
                  'type': kind if name in failed else None} for name in self.data['tests']]
        body = dict({'suite': 'JournalRaceContract', 'runtime': self.RUNTIME, 'cases': cases}, **changes)
        (root / 'target').mkdir(exist_ok=True)
        (root / r.PYTHON_REPORT).write_text(json.dumps(body))

    def fake_command(self, stages, reference_failed=(), mutate=None):
        def command(argv, **kwargs):
            if argv[1:5] == ['-I', '-S', '-B', '-c']:
                return 0, json.dumps(self.RUNTIME).encode(), b''
            root = Path(kwargs['cwd'])
            stages.append(root)
            self.assertEqual(argv, r.python_recipe(Path(argv[0]), root, self.spec))
            self.assertEqual(kwargs['env'], r.python_environment(Path(kwargs['env']['HOME'])))
            self.assertEqual((root / self.spec['grader']).read_bytes(),
                             (r.FIXTURES / self.spec['grader_file']).read_bytes())
            self.assertEqual((root / 'TASK.md').read_bytes(), (r.FIXTURES / self.spec['task_file']).read_bytes())
            reference = root.name == 'grading-reference'
            self.assertEqual((root / self.SOURCE).read_bytes(),
                             b'reference' if reference else b'baseline:' + self.SOURCE.encode())
            if mutate:
                mutate(root)
            failed = reference_failed if reference else self.data['baseline_failures']
            self.report(root, failed)
            return (1 if failed else 0), b'', b''
        return command

    def qualify(self, data, snapshot, command):
        with patch.object(r, 'git', side_effect=lambda *a: snapshot if a[0] == 'archive' else b'reference'), \
                patch.object(r, 'command', side_effect=command):
            return r.qualify(data, None, 5, sys.executable)

    def test_spec_pins_single_overlay_exact_export_and_reference_delta(self):
        self.assertEqual(self.spec['runtime'], 'python')
        self.assertEqual(r.overlay_sources(self.spec), (self.SOURCE,))
        self.assertEqual(self.spec['export_paths'], self.EXPORT)
        self.assertNotIn(self.spec['grader'], self.EXPORT)
        self.assertEqual(self.spec['changed_paths'], frozenset((
            'docs/durable-capture.md', 'docs/superpowers/plans/2026-10-03-outbox-journal-race.md',
            self.SOURCE, 'scripts/hooks/test_capture_outbox.py')))
        baseline = self.data['tracked_hashes'][self.spec['baseline']]
        reference = self.data['tracked_hashes'][self.spec['reference']]
        # The reference test is never overlaid, so its hash is pinned only where it is staged.
        self.assertEqual(set(baseline) - set(reference), {'scripts/hooks/test_capture_outbox.py'})
        self.assertNotEqual(baseline[self.SOURCE], reference[self.SOURCE])
        for path in set(reference) - {self.SOURCE}:
            self.assertEqual(baseline[path], reference[path])
        self.assertEqual(len(self.data['tests']), 7)
        self.assertEqual(sorted(self.data['baseline_failures']), sorted((
            'test_commit_between_journal_open_and_check_keeps_capture',
            'test_queue_open_during_concurrent_commit',
            'test_safe_replacement_journal_is_rechecked_not_reused')))

    def test_fixed_isolated_recipe_and_scrubbed_environment(self):
        hostile = {'PYTHONPATH': '/hostile', 'PYTHONSTARTUP': '/hostile/x.py', 'PATH': '/hostile/bin',
                   'SBA_TOKEN': 'secret', 'VIRTUAL_ENV': '/hostile/venv', 'HOME': '/hostile/home'}
        with patch.dict(os.environ, hostile):
            env = r.python_environment(self.root)
        self.assertEqual(env, {'PATH': os.defpath, 'HOME': str(self.root), 'TMPDIR': str(self.root),
                               'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8'})
        argv = r.python_recipe(Path('/interpreter/python3'), self.root, self.spec)
        self.assertEqual(argv, ['/interpreter/python3', '-I', '-S', '-B', str(self.root / self.spec['grader']),
                                str(self.root / self.SOURCE), str(self.root / r.PYTHON_REPORT)])

    def test_interpreter_must_be_absolute_executable_python39(self):
        with self.assertRaisesRegex(r.FixtureError, 'absolute_python_required'):
            r.python_interpreter('python3')
        with self.assertRaisesRegex(r.FixtureError, 'tool_unavailable'):
            r.python_interpreter(str(self.root / 'missing-python'))
        self.assertEqual(r.python_interpreter(None), Path(sys.executable).resolve())
        for out, code in ((json.dumps(dict(self.RUNTIME, python='3.8.18')).encode(), 0), (b'not json', 0),
                          (json.dumps(self.RUNTIME).encode(), 1), (json.dumps({'python': '3.9.6'}).encode(), 0)):
            with self.subTest(out=out, code=code), patch.object(r, 'command', return_value=(code, out, b'')), \
                    self.assertRaisesRegex(r.FixtureError, 'python39_required'):
                r.python_runtime(Path(sys.executable), self.root)
        with patch.object(r, 'command', return_value=(0, json.dumps(self.RUNTIME).encode(), b'')) as probe:
            self.assertEqual(r.python_runtime(Path(sys.executable), self.root), self.RUNTIME)
        self.assertEqual(probe.call_args[0][0][1:5], ['-I', '-S', '-B', '-c'])

    def test_export_is_an_exact_allowlist(self):
        extra = archive([('scripts/hooks/other.py', b'x', tarfile.REGTYPE)])
        with self.assertRaisesRegex(r.FixtureError, 'unsafe_snapshot_entry'):
            r.extract_snapshot(extra, self.root, self.spec['export_paths'])
        for name in ('pom.xml', 'scripts/hooks', 'scripts/hooks/fixtures/other.json'):
            self.assertFalse(r.allowed_export(name, self.spec['export_paths']))
        data = copy.deepcopy(self.data)
        missing = self.snapshot(data, [p for p in self.EXPORT if not p.endswith('.json')])
        stages = []
        with self.assertRaisesRegex(r.FixtureError, 'worker_export_mismatch'):
            self.qualify(data, missing, self.fake_command(stages))
        self.assertEqual(stages, [])

    def test_private_trees_stage_grader_and_reference_overlay_then_clean(self):
        data = copy.deepcopy(self.data)
        stages = []
        result = self.qualify(data, self.snapshot(data), self.fake_command(stages))
        self.assertEqual(result['qualification'], 'passed')
        self.assertEqual(result['runtime'], self.RUNTIME)
        self.assertEqual(result['results']['baseline']['failed'], sorted(data['baseline_failures']))
        self.assertEqual(result['results']['reference'], dict(result['results']['reference'], failed=[], passed=7))
        self.assertEqual([root.name for root in stages], ['grading-baseline', 'grading-reference'])
        self.assertTrue(all(not root.parent.exists() for root in stages))

    def test_wrong_baseline_or_reference_outcomes_are_refused(self):
        for reference_failed, baseline_override, expected in (
                (('test_ordinary_enqueue_preserved',), None, 'reference_not_correct'),
                ((), ['test_queue_open_during_concurrent_commit'], 'baseline_not_reproduced')):
            with self.subTest(expected=expected):
                data = copy.deepcopy(self.data)
                snapshot = self.snapshot(data)
                stages = []
                command = self.fake_command(stages, reference_failed)
                if baseline_override:
                    data['baseline_failures'] = baseline_override
                with self.assertRaisesRegex(r.FixtureError, expected):
                    self.qualify(data, snapshot, command)
                self.assertTrue(all(not root.parent.exists() for root in stages))

    def test_changed_tree_or_controller_inputs_during_run_are_refused(self):
        copied = self.root / 'reviewed'
        shutil.copytree(r.FIXTURES, copied)
        for target, expected in ((self.SOURCE, 'grading_inputs_changed'),
                                 ('pycache', 'grading_inputs_changed'),
                                 (self.spec['manifest'], 'fixture_manifest_changed'),
                                 (self.spec['grader_file'], 'trusted_fixture_changed')):
            with self.subTest(target=target), patch.object(r, 'FIXTURES', copied):
                original = {name: (copied / name).read_bytes()
                            for name in (self.spec['manifest'], self.spec['grader_file'])}
                data = r.fixture('journal-race')
                snapshot = self.snapshot(data)

                def mutate(root, target=target):
                    if target == self.SOURCE:
                        (root / target).write_bytes(b'candidate rewrite')
                    elif target == 'pycache':
                        (root / 'grader' / '__pycache__').mkdir()
                        (root / 'grader' / '__pycache__' / 'x.pyc').write_bytes(b'bytecode')
                    else:
                        (copied / target).write_bytes(b'changed during selected run')
                stages = []
                try:
                    with self.assertRaisesRegex(r.FixtureError, expected):
                        self.qualify(data, snapshot, self.fake_command(stages, mutate=mutate))
                finally:
                    for name, raw in original.items():
                        (copied / name).write_bytes(raw)
                self.assertEqual(len(stages), 1)
                self.assertTrue(all(not root.parent.exists() for root in stages))

    def test_report_classification_is_strict(self):
        cases = [
            ('race_not_established', dict(failed=self.data['tests'][:1], outcome='error', kind='RaceNotEstablished'), 1),
            ('skipped_tests', dict(failed=self.data['tests'][:1], outcome='skipped', kind=None), 1),
            ('test_execution_error', dict(failed=self.data['tests'][:1], outcome='error', kind='OperationalError'), 1),
            ('non_behavioral_test_failure', dict(failed=self.data['tests'][:1], kind='OutboxError'), 1),
            ('invalid_test_report', dict(failed=self.data['tests'][:1], outcome='xfail'), 1),
            ('inconsistent_build_exit', dict(failed=self.data['tests'][:1]), 0),
            ('inconsistent_build_exit', dict(), 1),
            ('inconsistent_build_exit', dict(), -9),
            ('python_runtime_changed', dict(runtime=dict(self.RUNTIME, sqlite='3.0.0')), 0),
            ('test_inventory_mismatch', dict(suite='OtherSuite'), 0),
            ('invalid_test_report', dict(extra=True), 0),
        ]
        for expected, options, code in cases:
            with self.subTest(expected=expected, code=code):
                root = Path(tempfile.mkdtemp(dir=str(self.root)))
                self.report(root, **options)
                with self.assertRaisesRegex(r.FixtureError, expected):
                    r.classify_python_report(root, self.data, code, self.RUNTIME)
        for mutation in ('missing', 'duplicate', 'unexpected'):
            with self.subTest(mutation=mutation):
                root = Path(tempfile.mkdtemp(dir=str(self.root)))
                self.report(root)
                body = json.loads((root / r.PYTHON_REPORT).read_text())
                if mutation == 'missing':
                    body['cases'].pop()
                elif mutation == 'duplicate':
                    body['cases'][-1] = dict(body['cases'][0])
                else:
                    body['cases'][-1]['name'] = 'test_unreviewed'
                (root / r.PYTHON_REPORT).write_text(json.dumps(body))
                with self.assertRaisesRegex(r.FixtureError, 'test_inventory_mismatch'):
                    r.classify_python_report(root, self.data, 0, self.RUNTIME)
        root = Path(tempfile.mkdtemp(dir=str(self.root)))
        with self.assertRaisesRegex(r.FixtureError, 'missing_or_unexpected_test_report'):
            r.classify_python_report(root, self.data, 0, self.RUNTIME)
        (root / 'target').mkdir()
        (root / 'elsewhere.json').write_text('{}')
        (root / r.PYTHON_REPORT).symlink_to(root / 'elsewhere.json')
        with self.assertRaisesRegex(r.FixtureError, 'missing_or_unexpected_test_report'):
            r.classify_python_report(root, self.data, 0, self.RUNTIME)
        (root / r.PYTHON_REPORT).unlink()
        (root / r.PYTHON_REPORT).write_text('not json')
        with self.assertRaisesRegex(r.FixtureError, 'invalid_test_report'):
            r.classify_python_report(root, self.data, 0, self.RUNTIME)
        root = Path(tempfile.mkdtemp(dir=str(self.root)))
        self.report(root, failed=self.data['baseline_failures'])
        self.assertEqual(r.classify_python_report(root, self.data, 1, self.RUNTIME),
                         {'tests': 7, 'passed': 4, 'failed': sorted(self.data['baseline_failures'])})

    def test_main_routes_python_fixture_without_maven_and_java_unchanged(self):
        with patch.object(r, 'verify_pins'), patch.object(r, 'qualify', return_value={'qualification': 'passed'}) as build:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(r.main(['verify', '--execute', '--fixture', 'journal-race',
                                         '--python', sys.executable]), 0)
                self.assertEqual(build.call_args[0][1:], (None, 180, sys.executable))
                self.assertEqual(r.main(['verify', '--execute', '--fixture', 'capture-ack']), 0)
                self.assertEqual(len(build.call_args[0]), 3)
                self.assertIsInstance(build.call_args[0][1], Path)
        with patch.object(r, 'python_runtime', side_effect=AssertionError('Java must not probe Python')), \
                patch.object(r, 'git', return_value=b''), self.assertRaisesRegex(r.FixtureError, 'maven_cache_missing'):
            r.qualify(r.fixture('capture-ack'), self.root / 'missing-cache', 1)

    def test_all_prior_fixture_bytes_remain_pinned(self):
        for name, digest in (
                ('structured-redaction.json', '96f045b76bda691480286c532d4048ffa5cb13e03da106081f73c8629f081b2a'),
                ('summary-export.json', 'd462253622dedcdae6dece623db4574d850a0c7a569db1fbd14ebcf9e8c8084d'),
                ('event-chronology.json', '11dc2100b2d7ab9cf2e998dc8a73ba57148994e6cc1a9ea6b5297709a9cc79aa'),
                ('capture-ack.json', 'a24f22a02cf71b1218aba74a442c4363abff0363c3c5cac0c8060051a02dc07e')):
            self.assertEqual(r.sha((r.FIXTURES / name).read_bytes()), digest)
        for name in ('structured-redaction', 'summary-export', 'event-chronology', 'capture-ack'):
            self.assertNotIn('runtime', r.specification(name))
            r.fixture(name)


class JournalRaceGraderClassificationTests(unittest.TestCase):
    """NAT-324: grader-owned failures are infrastructure; only a proved race yields behavior.

    Runs single grader tests in-process against the repository's current outbox (which carries
    the reviewed fix) with one injected fault, then classifies the result like the qualifier.
    """
    RACE = 'test_commit_between_journal_open_and_check_keeps_capture'
    REPLACEMENT = 'test_safe_replacement_journal_is_rechecked_not_reused'

    def load(self, path, name):
        spec = importlib.util.spec_from_file_location(name, str(path))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def run_grader_test(self, name, patches):
        grader = self.load(r.FIXTURES / 'journal_race_contract.py', 'journal_race_contract_under_test')
        grader.outbox = self.load(r.REPO / 'scripts/hooks/capture_outbox.py', 'capture_outbox_under_test')
        result = grader.Recorder()
        with contextlib.ExitStack() as stack:
            for factory in patches:
                stack.enter_context(factory(grader))
            unittest.defaultTestLoader.loadTestsFromName(name, grader.JournalRaceContract).run(result)
        return result.cases[name]

    @staticmethod
    def failing_holder(statement, nth):
        def factory(grader):
            real = grader.JournalRaceContract.holder

            class Holder:
                def __init__(self, connection):
                    self.connection, self.seen = connection, 0

                def execute(self, sql, *args):
                    if sql == statement:
                        self.seen += 1
                        if self.seen == nth:
                            raise sqlite3.OperationalError('injected fixture failure')
                    return self.connection.execute(sql, *args)

                @property
                def in_transaction(self):
                    return self.connection.in_transaction

            return patch.object(grader.JournalRaceContract, 'holder',
                                lambda self, directory: Holder(real(self, directory)))
        return factory

    def assert_rejected_by_qualifier(self, name, case):
        data = r.fixture('journal-race')
        runtime = {'python': '3.9.6', 'sqlite': '3.54.0', 'platform': 'Darwin-arm64'}
        cases = [{'name': test, 'outcome': 'failure' if test in data['baseline_failures'] else 'pass',
                  'type': 'AssertionError' if test in data['baseline_failures'] else None}
                 for test in data['tests'] if test != name] + [case]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'target').mkdir()
            (root / r.PYTHON_REPORT).write_text(json.dumps(
                {'suite': 'JournalRaceContract', 'runtime': runtime, 'cases': cases}))
            with self.assertRaisesRegex(r.FixtureError, 'race_not_established'):
                r.classify_python_report(root, data, 1, runtime)

    def test_pre_proof_fstat_failure_is_infrastructure(self):
        fstat = lambda grader: patch.object(grader, 'REAL_FSTAT', side_effect=OSError('injected fstat failure'))
        case = self.run_grader_test(self.RACE, [fstat])
        self.assertEqual((case['outcome'], case['type']), ('error', 'RaceNotEstablished'))
        self.assert_rejected_by_qualifier(self.RACE, case)

    def test_holder_commit_failure_is_infrastructure(self):
        case = self.run_grader_test(self.RACE, [self.failing_holder('COMMIT', 1)])
        self.assertEqual((case['outcome'], case['type']), ('error', 'RaceNotEstablished'))
        self.assert_rejected_by_qualifier(self.RACE, case)

    def test_replacement_setup_failure_after_proof_is_infrastructure(self):
        # The first BEGIN IMMEDIATE precedes the race; the second creates the replacement after proof.
        case = self.run_grader_test(self.REPLACEMENT, [self.failing_holder('BEGIN IMMEDIATE', 2)])
        self.assertEqual((case['outcome'], case['type']), ('error', 'RaceNotEstablished'))
        self.assert_rejected_by_qualifier(self.REPLACEMENT, case)

    def test_candidate_operational_error_after_proof_is_behavioral(self):
        def candidate(grader):
            original = grader.outbox.Queue.enqueue
            calls = []

            def enqueue(queue, origin, data):
                calls.append(origin)
                if len(calls) == 1:
                    return original(queue, origin, data)  # Seed row before the race.
                queue.check_files()  # The real journal race is proved here.
                raise sqlite3.OperationalError('candidate disk I/O error')
            return patch.object(grader.outbox.Queue, 'enqueue', enqueue)
        case = self.run_grader_test(self.RACE, [candidate])
        self.assertEqual((case['outcome'], case['type']), ('failure', 'AssertionError'))

    def test_candidate_exceptions_before_proof_are_infrastructure(self):
        # No candidate outcome counts as behavior before the journal race is proved.
        for raised in (AssertionError('candidate assertion'), sqlite3.OperationalError('candidate I/O')):
            with self.subTest(raised=type(raised).__name__):
                def candidate(grader, raised=raised):
                    original = grader.outbox.Queue.enqueue
                    calls = []

                    def enqueue(queue, origin, data):
                        calls.append(origin)
                        if len(calls) == 1:
                            return original(queue, origin, data)  # Seed row before the race.
                        raise raised  # Before any journal open.
                    return patch.object(grader.outbox.Queue, 'enqueue', enqueue)
                case = self.run_grader_test(self.RACE, [candidate])
                self.assertEqual((case['outcome'], case['type']), ('error', 'RaceNotEstablished'))
                self.assert_rejected_by_qualifier(self.RACE, case)

    def test_candidate_assertion_after_proof_is_behavioral(self):
        def candidate(grader):
            original = grader.outbox.Queue.enqueue
            calls = []

            def enqueue(queue, origin, data):
                calls.append(origin)
                if len(calls) == 1:
                    return original(queue, origin, data)
                queue.check_files()  # The real journal race is proved here.
                raise AssertionError('candidate assertion after proof')
            return patch.object(grader.outbox.Queue, 'enqueue', enqueue)
        case = self.run_grader_test(self.RACE, [candidate])
        self.assertEqual((case['outcome'], case['type']), ('failure', 'AssertionError'))

    def test_unmodified_grader_passes_against_current_outbox(self):
        for name in (self.RACE, self.REPLACEMENT):
            self.assertEqual(self.run_grader_test(name, [])['outcome'], 'pass')


if __name__ == '__main__':
    unittest.main()
