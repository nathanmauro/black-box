import copy
import json
import os
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
