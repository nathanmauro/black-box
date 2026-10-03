import copy
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import resumption_eval as e


def fixture():
    return {'schema_version': 1, 'tasks': [
        {'id': f'task-{n:02d}', 'event_id': f'00000000-0000-0000-0000-{n:012d}',
         'prompt': 'Resume the fixture audit', 'shared_context': 'shared source',
         'as_of': 'fixture checkpoint', 'cutoff_epoch': 2_000_000_000,
         'provenance': 'fixture only, never real evidence',
         'facts': [{'key': 'value', 'question': 'Which value?', 'expected': 'secret-answer',
                    'evidence': 'independent PRIVATE source'}]} for n in range(1, 6)]}


class Server(BaseHTTPRequestHandler):
    calls = []
    mode = 'ok'

    def log_message(self, *args):
        pass

    def respond(self, obj):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode())

    def do_GET(self):
        from urllib.parse import parse_qs, urlsplit
        event = parse_qs(urlsplit(self.path).query)['scope'][0]
        if self.mode == 'redirect':
            self.send_response(302)
            self.send_header('Location', 'http://example.com/private')
            self.end_headers()
        else:
            self.respond({'mode': 'lexical', 'items': [{'eventId': event, 'kind': 'handoff',
                'observedAt': '2026-01-01T00:00:00Z', 'headline': 'PRIVATE-HANDOFF'}]})

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.calls.append(data)
        self.respond({'model': 'local-model', 'choices': [{'finish_reason': 'stop',
                      'message': {'content': json.dumps({'value': 'secret-answer'})}}]})


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        # Resolve macOS /var -> /private/var before testing intentional symlink rejection.
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.data = fixture()

    def tearDown(self):
        self.temp.cleanup()

    def test_endpoint_rejects_remote_aliases_credentials_paths(self):
        for origin in ('https://127.0.0.1:9', 'http://example.com:8', 'http://localhost:8',
                       'http://127.0.0.1:8/private', 'http://u:p@127.0.0.1:8',
                       'http://127.0.0.1:8?query=x', 'http://127.0.0.1:8#x',
                       'file:///private', 'http://127.0.0.1'):
            with self.subTest(origin=origin), self.assertRaises(e.EvalError):
                e.endpoint(origin)
        self.assertEqual(e.endpoint('http://[::1]:8/'), 'http://[::1]:8')

    def test_owner_only_artifacts(self):
        root = e.private_dir(self.root / 'new')
        e.save(root / 'x.json', {'private': True})
        self.assertEqual(root.stat().st_mode & 0o777, 0o700)
        self.assertEqual((root / 'x.json').stat().st_mode & 0o777, 0o600)
        with self.assertRaises(e.EvalError):
            e.private_dir(root)
        with self.assertRaises(FileExistsError):
            e.save(root / 'x.json', {})

    def test_git_and_symlink_output_refused(self):
        (self.root / '.git').write_text('gitdir: elsewhere')
        with self.assertRaises(e.EvalError):
            e.private_dir(self.root / 'new')
        (self.root / '.git').unlink()
        (self.root / 'link').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(e.EvalError):
            e.private_dir(self.root / 'link' / 'new')

    def test_manifest_permissions_and_validation(self):
        path = self.root / 'manifest.json'
        path.write_text(json.dumps(self.data))
        path.chmod(0o644)
        with self.assertRaises(e.EvalError):
            e.load_manifest(path)
        path.chmod(0o600)
        self.assertEqual(e.load_manifest(path), self.data)

    def test_five_distinct_real_sources_required(self):
        for mutation in ('count', 'duplicate', 'missing_evidence', 'unsafe_alias'):
            data = copy.deepcopy(self.data)
            if mutation == 'count':
                data['tasks'].pop()
            elif mutation == 'duplicate':
                data['tasks'][1]['event_id'] = data['tasks'][0]['event_id']
            elif mutation == 'missing_evidence':
                data['tasks'][0]['facts'][0]['evidence'] = ''
            else:
                data['tasks'][0]['id'] = '/Users/PRIVATE'
            with self.subTest(mutation=mutation), self.assertRaises(e.EvalError):
                e.validate(data)

    def test_prompt_answer_key_hidden_and_shared_context_equal(self):
        task = self.data['tasks'][0]
        bare = e.prompt(task)
        recalled = e.prompt(task, {'headline': 'memory'})
        self.assertNotIn('secret-answer', json.dumps(recalled))
        self.assertNotIn('PRIVATE', json.dumps(recalled))
        a, b = json.loads(bare[1]['content']), json.loads(recalled[1]['content'])
        del b['historical_handoff']
        self.assertEqual(a, b)
        self.assertEqual(bare[0], recalled[0])

    def test_exact_scoring_missing_and_wrong_are_distinct(self):
        task = self.data['tasks'][0]
        self.assertTrue(e.score(task, {'value': 'secret-answer'})['passed'])
        self.assertEqual(e.score(task, {'value': None})['missing'], 1)
        self.assertEqual(e.score(task, {'value': 'invented'})['incorrect'], 1)
        self.assertEqual(e.score(task, {'value': ['secret-answer']})['incorrect'], 1)
        self.assertFalse(e.score(task, {'value': 'secret-answer', 'claim': 'x'})['passed'])
        self.assertFalse(e.score(task, [])['passed'])

    def test_schedule_frozen_paired_and_counterbalanced(self):
        plan = e.schedule(self.data['tasks'], 42)
        self.assertEqual(plan, e.schedule(self.data['tasks'], 42))
        self.assertEqual(len(plan), 10)
        self.assertEqual(len({(p['task'], p['arm']) for p in plan}), 10)
        for i in range(0, 10, 2):
            self.assertEqual(plan[i]['task'], plan[i + 1]['task'])
            self.assertNotEqual(plan[i]['arm'], plan[i + 1]['arm'])
        self.assertEqual(sum(plan[i]['arm'] == 'recall' for i in range(0, 10, 2)), 2)

    def test_recall_fails_closed_and_filters_unrelated(self):
        task = self.data['tasks'][0]
        exact = {'eventId': task['event_id'], 'kind': 'handoff', 'observedAt': 100,
                 'headline': 'real', 'private_session_metadata': 'never-forward'}
        for response in ({'mode': 'lexical', 'items': None},
                         {'mode': 'lexical', 'items': 1},
                         {'mode': 'lexical', 'items': [{**exact, 'headline': None}]},
                         {'mode': 'lexical', 'items': [{**exact, 'openLoops': 'bad'}]},
                         {'mode': 'hybrid', 'items': [exact]},
                         {'mode': 'lexical', 'items': []},
                         {'mode': 'lexical', 'truncated': True, 'items': [exact]},
                         {'mode': 'lexical', 'items': [exact, exact]},
                         {'mode': 'lexical', 'items': [{**exact, 'observedAt': 3_000_000_000}]}):
            with patch.object(e, 'request', return_value=response), self.assertRaises(e.EvalError):
                e.recall('http://127.0.0.1:1', task, 8760, 10)
        with patch.object(e, 'request', return_value={'mode': 'lexical', 'items': [exact, {'eventId': 'other'}]}):
            result = e.recall('http://127.0.0.1:1', task, 8760, 10)
        self.assertNotIn('private_session_metadata', result)

    def recalled_timestamp(self, observed, cutoff=2_000_000_000):
        task = {**self.data['tasks'][0], 'cutoff_epoch': cutoff}
        item = {'eventId': task['event_id'], 'kind': 'handoff', 'observedAt': observed,
                'headline': 'Synthetic timestamp fixture'}
        with patch.object(e, 'request', return_value={'mode': 'lexical', 'items': [item]}):
            return e.recall('http://127.0.0.1:1', task, 8760, 10)

    def test_java_instant_fractions_and_explicit_offsets_preserve_source_timestamp(self):
        for fraction in ('', '.1', '.123', '.123456', '.1234567', '.123456789'):
            for clock, zone in (('2026-01-01T00:00:00', 'Z'),
                                ('2026-01-01T00:00:00', '+00:00'),
                                ('2026-01-01T05:30:00', '+05:30'),
                                ('2025-12-31T20:00:00', '-04:00')):
                observed = clock + fraction + zone
                with self.subTest(observed=observed):
                    result = self.recalled_timestamp(observed, 1767225601)
                    self.assertEqual(result['observedAt'], observed)

    def test_timestamp_cutoff_does_not_round_away_future_nanoseconds(self):
        cases = [
            ('2026-01-01T00:00:00.123455999Z', 1767225600.123456, True),
            ('2026-01-01T00:00:00.123456000Z', 1767225600.123456, True),
            ('2026-01-01T00:00:00.123456001Z', 1767225600.123456, False),
            ('2026-01-01T05:30:00.123456001+05:30', 1767225600.123456, False),
            ('2025-12-31T20:00:00.123456000-04:00', 1767225600.123456, True),
            ('2026-01-01T00:00:00.000000001Z', 1767225600, False),
            ('2026-01-01T00:00:00Z', 1767225600, True),
            ('2026-01-01T00:00:00.999999999Z', 1767225601, True),
            ('1969-12-31T23:59:59.999999999Z', 0, True),
            (100, 100, True), (100.001, 100.001, True), (100.001, 100, False)]
        for observed, cutoff, accepted in cases:
            with self.subTest(observed=observed, cutoff=cutoff):
                if accepted:
                    self.assertEqual(self.recalled_timestamp(observed, cutoff)['observedAt'], observed)
                else:
                    with self.assertRaisesRegex(e.EvalError, 'handoff_after_cutoff_or_invalid_timestamp'):
                        self.recalled_timestamp(observed, cutoff)

    def test_timestamp_requires_valid_calendar_clock_fraction_and_timezone(self):
        invalid = ('', '2026-01-01', '2026-01-01T00:00:00', '2026-01-01T00:00:00.123456789',
                   '2026-02-29T00:00:00Z', '2026-13-01T00:00:00Z', '2026-01-01T24:00:00Z',
                   '2026-01-01T00:00:60Z', '2026-01-01T00:00:00.Z',
                   '2026-01-01T00:00:00.1234567890Z', '2026-01-01T00:00:00,123Z',
                   '2026-01-01T00:00:00+01:60', '2026-01-01T00:00:00+24:00',
                   '2026-01-01T00:00:00+01', '2026-01-01T00:00:00+0100',
                   '2026-01-01T00:00:00Z trailing', '2026-01-01T00:00:00Z\n',
                   True, None, float('inf'), float('-inf'), float('nan'))
        for observed in invalid:
            with self.subTest(observed=observed), self.assertRaises(e.EvalError):
                self.recalled_timestamp(observed)

    def test_hour_24_rejected_on_every_python_including_submicrosecond_offset_and_month_end(self):
        # Python 3.14 fromisoformat reads hour 24 as next-day midnight; fractions below one
        # microsecond normalize to zero, so they used to slip past as well.
        invalid = ('2026-01-01T24:00:00Z', '2026-01-01T24:00:00.000Z',
                   '2026-01-01T24:00:00.000000000Z', '2026-01-01T24:00:00.000000001Z',
                   '2026-01-01T24:00:00.000000999Z', '2026-01-01T24:00:00.000001Z',
                   '2026-01-01T24:00:00+05:30', '2026-01-01T24:00:00.000000500-04:00',
                   '2026-01-31T24:00:00-04:00', '2026-02-28T24:00:00Z',
                   '2028-02-29T24:00:00.000000001+00:00', '2026-12-31T24:00:00.000000999Z')
        for observed in invalid:
            with self.subTest(observed=observed):
                with self.assertRaisesRegex(e.EvalError, '^invalid_recall_timestamp$'):
                    e.instant_epoch(observed)
                with self.assertRaisesRegex(e.EvalError, '^invalid_recall_timestamp$'):
                    self.recalled_timestamp(observed)
        valid = (('2026-01-01T23:59:59.999999999Z', '1767311999.999999999'),
                 ('2026-01-31T23:59:59.000000001-04:00', '1769918399.000000001'),
                 ('2028-02-29T23:59:59.999999999+05:30', '1835461799.999999999'),
                 ('2026-12-31T23:59:59.000000999Z', '1798761599.000000999'))
        for observed, epoch in valid:
            with self.subTest(observed=observed):
                self.assertEqual(e.instant_epoch(observed), e.Decimal(epoch))
                self.assertEqual(self.recalled_timestamp(observed)['observedAt'], observed)

    def test_cli_refuses_hour_24_before_any_model_request_and_keeps_valid_controls(self):
        class HourServer(Server):
            calls = []
            recalls = []
            observed = None

            def do_GET(self):
                from urllib.parse import parse_qs, urlsplit
                event = parse_qs(urlsplit(self.path).query)['scope'][0]
                self.recalls.append(event)
                self.respond({'mode': 'lexical', 'truncated': False, 'items': [{
                    'eventId': event, 'kind': 'handoff', 'observedAt': self.observed,
                    'headline': 'PRIVATE synthetic handoff'}]})

        server = ThreadingHTTPServer(('127.0.0.1', 0), HourServer)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = f'http://127.0.0.1:{server.server_port}'
        # Scrubbed environment: no provider keys, proxies, or Black Box settings reach the CLI.
        env = {'PATH': os.environ.get('PATH', ''), 'PYTHONDONTWRITEBYTECODE': '1'}
        cases = [('hour24-zero', '2026-01-01T24:00:00Z', 'invalid_recall_timestamp'),
                 ('hour24-nano', '2026-01-01T24:00:00.000000001Z', 'invalid_recall_timestamp'),
                 ('hour24-subus', '2026-01-01T24:00:00.000000999+05:30', 'invalid_recall_timestamp'),
                 ('hour24-month-end', '2026-01-31T24:00:00-04:00', 'invalid_recall_timestamp'),
                 ('valid-nano', '2026-01-01T23:59:59.999999999Z', None),
                 ('valid-offset', '2026-01-31T23:59:59.000000001-04:00', None)]
        try:
            for name, observed, error in cases:
                with self.subTest(case=name):
                    HourServer.calls, HourServer.recalls, HourServer.observed = [], [], observed
                    manifest = self.root / (name + '.json')
                    e.save(manifest, self.data)
                    output = self.root / name
                    completed = subprocess.run([
                        sys.executable, str(Path(e.__file__).resolve()), str(manifest),
                        '--output', str(output), '--model-origin', origin, '--recall-origin', origin,
                        '--model', 'local-model'], capture_output=True, text=True, timeout=15, env=env)
                    report = json.loads((output / 'report.json').read_text())
                    self.assertEqual(report['usefulness_gate']['status'], 'not_cleared')
                    self.assertNotIn('PRIVATE', completed.stdout + completed.stderr)
                    self.assertGreaterEqual(len(HourServer.recalls), 1)
                    if error:
                        self.assertEqual(completed.returncode, 2)
                        self.assertEqual(HourServer.calls, [])
                        self.assertFalse((output / 'recalled.json').exists())
                        self.assertEqual(sum(a['not_attempted'] for a in report['arms'].values()), 10)
                        self.assertEqual(json.loads((output / 'preflight-error.json').read_text()),
                                         {'error_category': error})
                    else:
                        self.assertEqual(completed.returncode, 0, completed.stderr)
                        self.assertEqual(len(HourServer.calls), 10)
                        recalled = json.loads((output / 'recalled.json').read_text())
                        self.assertTrue(all(row['handoff']['observedAt'] == observed
                                            for row in recalled.values()))
                        prompts = [json.loads(p.read_text()) for p in sorted(output.glob('*-prompt.json'))]
                        handoffs = [json.loads(m[1]['content']).get('historical_handoff') for m in prompts]
                        self.assertEqual([h['observedAt'] for h in handoffs if h], [observed] * 5)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_documented_cli_accepts_nanoseconds_and_refuses_invalid_future_or_stale_evidence(self):
        class TimestampServer(Server):
            calls = []
            observed = '2026-01-01T00:00:00.123456789Z'
            empty = False

            def do_GET(self):
                from urllib.parse import parse_qs, urlsplit
                query = parse_qs(urlsplit(self.path).query)
                if query.get('withinHours') != ['8760']:
                    self.respond({'mode': 'invalid-fixture-query', 'items': []})
                    return
                items = [] if self.empty else [{
                    'eventId': query['scope'][0], 'kind': 'handoff',
                    'observedAt': self.observed, 'headline': 'PRIVATE synthetic handoff'}]
                self.respond({'mode': 'lexical', 'truncated': False, 'items': items})

        server = ThreadingHTTPServer(('127.0.0.1', 0), TimestampServer)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = f'http://127.0.0.1:{server.server_port}'
        cases = [('valid', '2026-01-01T00:00:00.123456789Z', 1767225601, False, None),
                 ('future', '2026-01-01T00:00:00.123456001Z', 1767225600.123456, False,
                  'handoff_after_cutoff_or_invalid_timestamp'),
                 ('invalid', '2026-13-01T00:00:00.123456789Z', 1767225601, False,
                  'invalid_recall_timestamp'),
                 ('stale', '2026-01-01T00:00:00Z', 1767225601, True, 'exact_handoff_missing')]
        try:
            for name, observed, cutoff, empty, error in cases:
                with self.subTest(case=name):
                    TimestampServer.calls, TimestampServer.observed, TimestampServer.empty = [], observed, empty
                    data = copy.deepcopy(self.data)
                    for task in data['tasks']:
                        task['cutoff_epoch'] = cutoff
                    manifest = self.root / (name + '.json')
                    e.save(manifest, data)
                    output = self.root / name
                    completed = subprocess.run([
                        sys.executable, str(Path(e.__file__).resolve()), str(manifest),
                        '--output', str(output), '--model-origin', origin, '--recall-origin', origin,
                        '--model', 'local-model', '--within-hours', '8760'],
                        capture_output=True, text=True, timeout=15,
                        env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
                    report = json.loads((output / 'report.json').read_text())
                    self.assertEqual(report['usefulness_gate']['status'], 'not_cleared')
                    self.assertNotIn('PRIVATE', completed.stdout)
                    if error:
                        self.assertEqual(completed.returncode, 2)
                        self.assertEqual(len(TimestampServer.calls), 0)
                        self.assertEqual(sum(a['not_attempted'] for a in report['arms'].values()), 10)
                        self.assertEqual(json.loads((output / 'preflight-error.json').read_text()),
                                         {'error_category': error})
                    else:
                        self.assertEqual(completed.returncode, 0, completed.stderr)
                        self.assertEqual(len(TimestampServer.calls), 10)
                        self.assertTrue(report['all_ten_graded'])
                        recalled = json.loads((output / 'recalled.json').read_text())
                        self.assertTrue(all(row['handoff']['observedAt'] == observed for row in recalled.values()))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_unknown_usage_stays_unknown(self):
        self.assertIsNone(e.usage_of({}))
        self.assertEqual(e.usage_of({'usage': {'prompt_tokens': True}})['prompt_tokens'], None)
        self.assertEqual(e.usage_of({'usage': {'prompt_tokens': 0}})['prompt_tokens'], 0)

    def fake_run(self, responses):
        with patch.object(e, 'recall', return_value={'headline': 'PRIVATE'}), patch.object(e, 'request', side_effect=responses):
            return e.run(self.data, self.root / 'run', 'http://127.0.0.1:1',
                         'http://127.0.0.1:2', 'local-model')

    def test_infrastructure_failure_keeps_all_scheduled(self):
        result = self.fake_run([e.EvalError('local_endpoint_failed')])
        self.assertFalse(result['all_ten_graded'])
        self.assertIn('incomplete run', e.markdown(result))
        self.assertEqual(sum(a['infrastructure_errors'] for a in result['arms'].values()), 1)
        self.assertEqual(sum(a['not_attempted'] for a in result['arms'].values()), 9)
        trials = json.loads((self.root / 'run/trials.json').read_text())
        self.assertEqual(len(trials), 10)
        self.assertEqual(sum(t['status'] == 'not_attempted' for t in trials), 9)
        self.assertEqual(result['usefulness_gate']['status'], 'not_cleared')

    def test_preflight_failure_preserves_report(self):
        with patch.object(e, 'recall', side_effect=e.EvalError('exact_handoff_missing')):
            result = e.run(self.data, self.root / 'run', 'http://127.0.0.1:1', 'http://127.0.0.1:2', 'm')
        self.assertFalse(result['all_ten_graded'])
        self.assertTrue((self.root / 'run/preflight-error.json').is_file())

    def test_invalid_model_output_is_failed_trial_not_dropped(self):
        response = {'model': 'local-model', 'choices': [{'finish_reason': 'stop', 'message': {'content': 'not JSON PRIVATE'}}]}
        result = self.fake_run([response] * 10)
        self.assertTrue(result['all_ten_graded'])
        self.assertEqual(result['arms']['recall']['missing'], 5)
        self.assertEqual(result['arms']['recall']['passed'], 0)

    def test_model_mismatch_stops_spending(self):
        result = self.fake_run([{'model': 'other', 'choices': []}])
        self.assertFalse(result['all_ten_graded'])

    def test_truncated_model_response_fails(self):
        response = {'model': 'local-model', 'choices': [{'finish_reason': 'length', 'message': {'content': '{"value":"secret-answer"}'}}]}
        result = self.fake_run([response] * 10)
        self.assertEqual(result['arms']['recall']['passed'], 0)

    def test_real_http_cli_path_and_public_allowlist(self):
        Server.calls, Server.mode = [], 'ok'
        server = ThreadingHTTPServer(('127.0.0.1', 0), Server)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = f'http://127.0.0.1:{server.server_port}'
        try:
            with patch.dict(os.environ, {'http_proxy': 'http://127.0.0.1:1', 'HTTP_PROXY': 'http://127.0.0.1:1'}):
                result = e.run(self.data, self.root / 'run', origin, origin, 'local-model')
            self.assertEqual(len(Server.calls), 10)
            self.assertTrue(result['all_ten_graded'])
            self.assertEqual(result['arms']['recall']['passed'], 5)
            self.assertEqual(result['usefulness_gate']['status'], 'not_cleared')
            public = json.dumps(result) + e.markdown(result)
            for secret in ('PRIVATE', 'secret-answer', '00000000-', str(self.root)):
                self.assertNotIn(secret, public)
            self.assertIsNone(result['arms']['recall']['usage']['total_tokens'])
            Server.mode = 'redirect'
            with self.assertRaises(e.EvalError):
                e.request(origin, '/api/recall?scope=x')
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
