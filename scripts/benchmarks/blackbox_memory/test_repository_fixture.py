import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import signal
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
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.data = r.fixture()
        (self.root / 'settings').write_bytes(r.SETTINGS)

    def reports(self, failed=(), skipped=(), errors=()):
        folder = self.root / 'target/surefire-reports'
        folder.mkdir(parents=True, exist_ok=True)
        suite = ET.Element('testsuite', name=r.CLASS, tests=str(len(self.data['tests'])),
                           failures=str(len(failed)), skipped=str(len(skipped)), errors=str(len(errors)))
        for name in self.data['tests']:
            case = ET.SubElement(suite, 'testcase', name=name, classname=r.CLASS)
            if name in failed:
                ET.SubElement(case, 'failure', type='org.opentest4j.AssertionFailedError')
            if name in skipped:
                ET.SubElement(case, 'skipped')
            if name in errors:
                ET.SubElement(case, 'error', type='java.lang.IllegalStateException')
        path = folder / ('TEST-' + r.CLASS + '.xml')
        ET.ElementTree(suite).write(path)
        return path

    def test_fixture_pins_and_public_development_status(self):
        self.assertEqual(self.data['baseline'], r.BASELINE)
        self.assertEqual(self.data['reference'], r.REFERENCE)
        self.assertTrue(self.data['development_only'])
        self.assertEqual(self.data['public_repository'], 'https://github.com/nathanmauro/black-box')
        self.assertEqual(len(self.data['tests']), 6)

    def test_changed_manifest_and_grader_fail_closed(self):
        copy_root = self.root / 'fixtures'
        shutil.copytree(r.FIXTURES, copy_root)
        with patch.object(r, 'FIXTURES', copy_root):
            (copy_root / 'RepositoryFixtureContractTest.java').write_text('changed')
            with self.assertRaisesRegex(r.FixtureError, 'trusted_fixture_changed'):
                r.fixture()
            (copy_root / 'structured-redaction.json').write_text('{}')
            with self.assertRaisesRegex(r.FixtureError, 'fixture_manifest_changed'):
                r.fixture()

    def test_linked_or_non_regular_trusted_inputs_are_never_read(self):
        copied = self.root / 'trusted'
        shutil.copytree(r.FIXTURES, copied)
        grader = copied / 'RepositoryFixtureContractTest.java'
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
        data['tracked_hashes'] = {rev: {'pom.xml': r.sha(b'reviewed')} for rev in (r.BASELINE, r.REFERENCE)}
        def git(*args):
            if args[0] == 'cat-file': return b'commit\n'
            if args[0] == 'rev-parse': return r.BASELINE.encode() + b'\n'
            if args[0] == 'diff': return '\n'.join(sorted(r.CHANGED_PATHS)).encode()
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
        self.assertEqual(r.classify_reports(self.root, self.data, 0)['passed'], 6)
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
        command = r.maven_recipe(self.root, self.root / 'settings', self.root / 'cache', self.root)
        self.assertIn('-o', command)
        self.assertIn('-gs', command)
        self.assertIn('-s', command)
        self.assertIn('-Dtest=' + r.CLASS, command)
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
        snapshot = archive([('pom.xml', b'trusted', tarfile.REGTYPE), (r.SOURCE, b'baseline', tarfile.REGTYPE),
                            ('README.md', b'public', tarfile.REGTYPE)])
        seen = []
        def stage(root, data, *args):
            seen.append(root)
            expected = args[-1]
            self.assertEqual(r.hashes(root), expected)
            worker = root.parent / 'worker-input'
            self.assertFalse((worker / r.GRADER).exists())
            self.assertEqual((worker / r.SOURCE).read_bytes(), b'baseline')
            self.assertFalse((worker / '.git').exists())
            self.assertTrue((root / r.GRADER).is_file())
            baseline = root.name.endswith('baseline')
            self.assertEqual((root / r.SOURCE).read_bytes(), b'baseline' if baseline else b'reference')
            return {'tests': 6, 'passed': 3 if baseline else 6,
                    'failed': sorted(data['baseline_failures']) if baseline else []}
        data = copy.deepcopy(self.data)
        for rev, text in ((r.BASELINE, b'baseline'), (r.REFERENCE, b'reference')):
            data['tracked_hashes'][rev] = {'pom.xml': r.sha(b'trusted'), r.SOURCE: r.sha(text)}
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
            data = r.fixture()
            (copied / 'RepositoryFixtureContractTest.java').write_text('unreviewed replacement')
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
suite = ET.Element('testsuite', name=CLASS, tests='6', failures=str(len(failed)), errors='0', skipped='0')
for name in names:
    case = ET.SubElement(suite, 'testcase', name=name, classname=CLASS)
    if name in failed: ET.SubElement(case, 'failure', type='org.opentest4j.AssertionFailedError')
reports = cwd / 'target/surefire-reports'
reports.mkdir(parents=True)
ET.ElementTree(suite).write(reports / ('TEST-' + CLASS + '.xml'))
sys.exit(1 if failed else 0)
""".replace('NAMES', repr(self.data['tests'])).replace('FAILURES', repr(self.data['baseline_failures']))
                .replace('CLASS', repr(r.CLASS)))
        launcher.chmod(0o700)
        caller = self.root / 'contaminated/caller'
        caller.mkdir(parents=True)
        for parent in (caller, caller.parent):
            (parent / '.mvn').mkdir()
            (parent / '.mvn/jvm.config').write_text('-XX:InvalidFixtureOption')
            (parent / '.mvn/maven.config').write_text('-DskipTests')
        snapshot = archive([('pom.xml', b'trusted', tarfile.REGTYPE), (r.SOURCE, b'baseline', tarfile.REGTYPE)])
        data = copy.deepcopy(self.data)
        for rev, text in ((r.BASELINE, b'baseline'), (r.REFERENCE, b'reference')):
            data['tracked_hashes'][rev] = {'pom.xml': r.sha(b'trusted'), r.SOURCE: r.sha(text)}
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
                self.assertEqual(r.main(['plan', '--output', str(output)]), 0)
            self.assertEqual(json.loads(printed.getvalue())['qualification'], 'planned')
            self.assertFalse(output.exists())
            build.assert_not_called()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(r.main(['verify']), 2)
            build.assert_not_called()

    def test_qualified_fixture_never_clears_usefulness_gate(self):
        with patch.object(r, 'verify_pins'), patch.object(r, 'qualify', return_value={'qualification': 'passed'}):
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(r.main(['verify', '--execute']), 0)
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


if __name__ == '__main__':
    unittest.main()
