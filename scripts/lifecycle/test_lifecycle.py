#!/usr/bin/env python3
"""Offline planner/adapter contracts and real CLI flows; no infrastructure credentials required."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from threading import Event, Thread
import unittest
from unittest.mock import patch

import lifecycle as lc

HERE = Path(__file__).resolve().parent


def fixtures():
    return (lc.read_json(HERE / 'fixtures/paused.manifest.json'),
            lc.read_json(HERE / 'fixtures/running.observed.json'))


def desired(m, state):
    result = deepcopy(m)
    result.update(desiredState=state, operationId='demo-' + state.lower())
    return result


def authorization(m, policy='discard'):
    return {**{key: deepcopy(m[key]) for key in ('operationId', 'deploymentId', 'workspaceId', 'resources', 'backupRef')},
            'dataPolicy': policy}


class LifecycleContracts(unittest.TestCase):
    def setUp(self):
        self.m, self.s = fixtures()

    def paused(self):
        return lc.reconcile_fake(self.m, lc.FakeInfrastructure(self.s))['observed']

    def test_plan_is_deterministic_and_pure(self):
        before = deepcopy((self.m, self.s))
        first = lc.plan(self.m, self.s)
        self.assertEqual(first, lc.plan(self.m, self.s))
        self.assertEqual(before, (self.m, self.s))
        self.assertEqual(['close_admission', 'drain', 'fence_workers', 'close_streams', 'stop_compute'], first['steps'])

    def test_pause_resume_retains_exact_capture_and_checkpoint(self):
        adapter = lc.FakeInfrastructure(self.s)
        pause = lc.reconcile_fake(self.m, adapter)
        self.assertEqual('SUCCEEDED', pause['outcome'])
        self.assertFalse(adapter.state['admissionOpen'])
        self.assertEqual('PAUSED', lc.observed_state(self.m, adapter.state))
        resume = desired(self.m, 'RUNNING')
        result = lc.reconcile_fake(resume, adapter)
        self.assertEqual('SUCCEEDED', result['outcome'])
        self.assertEqual('RUNNING', lc.observed_state(resume, adapter.state))
        self.assertEqual(self.s['captures'], adapter.state['captures'])
        self.assertEqual(self.s['checkpoints'], adapter.state['checkpoints'])
        self.assertEqual(['check_storage', 'start_exact_release', 'verify_readiness', 'open_admission'],
                         result['plan']['steps'])

    def test_repeated_pause_resume_and_destroy_are_noops(self):
        adapter = lc.FakeInfrastructure(self.s)
        for state in ('PAUSED', 'RUNNING', 'DESTROYED'):
            m = desired(self.m, state)
            auth = authorization(m) if state == 'DESTROYED' else None
            self.assertEqual('SUCCEEDED', lc.reconcile_fake(m, adapter, auth)['outcome'])
            before, calls = adapter.observe(), list(adapter.calls)
            self.assertEqual([], lc.plan(m, adapter.observe(), auth)['steps'])
            lc.reconcile_fake(m, adapter, auth)
            self.assertEqual(before, adapter.observe())
            self.assertEqual(calls, adapter.calls)

    def test_interruption_after_every_pause_resume_destroy_step_converges(self):
        for state in ('PAUSED', 'RUNNING', 'DESTROYED'):
            m = desired(self.m, state)
            s = self.paused() if state == 'RUNNING' else self.s
            auth = authorization(m) if state == 'DESTROYED' else None
            baseline = lc.reconcile_fake(m, lc.FakeInfrastructure(s), auth)['observed']
            count = len(lc.plan(m, s, auth)['steps'])
            for stop in range(1, count + 1):
                with self.subTest(state=state, stop=stop):
                    adapter = lc.FakeInfrastructure(s)
                    result = lc.reconcile_fake(m, adapter, auth, interrupt_after=stop)
                    self.assertEqual('INTERRUPTED', result['outcome'])
                    # Reconstruct the fake from its serialized journal, as after a process loss.
                    adapter = lc.FakeInfrastructure(json.loads(json.dumps(adapter.observe())))
                    result = lc.reconcile_fake(m, adapter, auth)
                    self.assertEqual('SUCCEEDED', result['outcome'])
                    for key in ('resources', 'captures', 'checkpoints', 'admissionOpen', 'streamsOpen', 'release', 'fence', 'workersFenced'):
                        self.assertEqual(baseline[key], adapter.state[key])
                    self.assertEqual([], lc.plan(m, adapter.observe(), auth)['steps'])

    def test_deadline_expiry_preserves_durable_data_and_blocks_stale_worker_and_resume(self):
        self.s['drainSeconds'] = 31
        adapter = lc.FakeInfrastructure(self.s)
        result = lc.reconcile_fake(self.m, adapter)
        self.assertEqual('RECOVERY_REQUIRED', result['outcome'])
        self.assertEqual('STOPPED', adapter.state['resources'][self.m['resources']['compute']]['state'])
        self.assertEqual(self.s['captures'], adapter.state['captures'])
        self.assertEqual(self.s['checkpoints'], adapter.state['checkpoints'])
        self.assertEqual(self.s['fence'] + 1, adapter.state['fence'])
        self.assertEqual('FAILED', adapter.state['operations'][self.m['operationId']]['status'])
        with self.assertRaisesRegex(lc.ContractError, 'Stale worker'):
            adapter.complete_job(self.s['fence'], 'job-001', {'sequence': 2})
        with self.assertRaisesRegex(lc.ContractError, 'Recovery required'):
            lc.plan(desired(self.m, 'RUNNING'), adapter.observe())
        before = adapter.observe()
        self.assertEqual('RECOVERY_REQUIRED', lc.reconcile_fake(self.m, adapter)['outcome'])
        self.assertEqual(before, adapter.observe())

    def test_accepted_worker_can_checkpoint_after_admission_closes(self):
        adapter = lc.FakeInfrastructure(self.s)
        adapter.perform('close_admission', self.m)
        adapter.complete_job(self.s['fence'], 'job-001', {'sequence': 2})
        self.assertEqual({'sequence': 2}, adapter.state['checkpoints']['job-001'])
        lc.reconcile_fake(self.m, adapter)
        with self.assertRaisesRegex(lc.ContractError, 'Stale worker'):
            adapter.complete_job(self.s['fence'], 'job-001', {'sequence': 3})

    def test_checkpoint_write_and_destroy_are_serialized(self):
        adapter = lc.FakeInfrastructure(self.s)
        ready, release = Event(), Event()
        worker_result, destroy_result = {}, {}

        class PausedCheckpointWrite(dict):
            def __deepcopy__(self, memo):
                return deepcopy(dict(self), memo)

            def __setitem__(self, key, value):
                # Both fence/count checks have completed when this write is attempted.
                ready.set()
                if not release.wait(5):
                    raise AssertionError('Checkpoint test write was not released')
                super().__setitem__(key, value)

        adapter.state['checkpoints'] = PausedCheckpointWrite(adapter.state['checkpoints'])
        manifest = desired(self.m, 'DESTROYED')

        def checkpoint():
            try:
                adapter.complete_job(self.s['fence'], 'job-001', {'sequence': 2})
            except Exception as error:
                worker_result['error'] = error

        def destroy():
            try:
                destroy_result.update(lc.reconcile_fake(manifest, adapter, authorization(manifest)))
            except Exception as error:
                destroy_result['error'] = error

        worker = Thread(target=checkpoint)
        reconciler = Thread(target=destroy)
        worker.start()
        try:
            self.assertTrue(ready.wait(5), 'Worker did not reach its checkpoint write')
            reconciler.start()
            reconciler.join(5)
            self.assertFalse(reconciler.is_alive(), 'Reconciler did not reject a held lock')
        finally:
            release.set()
            worker.join(5)
            if reconciler.ident is not None:
                reconciler.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual({}, worker_result)
        self.assertIsInstance(destroy_result.get('error'), lc.ContractError,
                              {'destroy': destroy_result, 'observed': adapter.observe()})
        self.assertIn('locked', str(destroy_result['error']))
        self.assertEqual({'sequence': 2}, adapter.state['checkpoints']['job-001'])
        self.assertEqual(0, adapter.state['activeJobs'])
        self.assertEqual([], adapter.calls)
        self.assertEqual('SUCCEEDED', lc.reconcile_fake(manifest, adapter, authorization(manifest))['outcome'])
        with self.assertRaisesRegex(lc.ContractError, 'Stale worker'):
            adapter.complete_job(self.s['fence'], 'job-001', {'sequence': 3})
        self.assertEqual({}, adapter.state['checkpoints'])
        self.assertEqual(0, adapter.state['activeJobs'])
        self.assertTrue(all(resource['state'] == 'ABSENT' for resource in adapter.state['resources'].values()))
        self.assertTrue(adapter.lock.acquire(blocking=False), 'Rejected checkpoint retained the lock')
        adapter.lock.release()

    def test_checkpoint_rejection_releases_the_adapter_lock(self):
        for change, expected in (({'fence': self.s['fence'] + 1}, 'Stale worker'),
                                 ({'activeJobs': 0}, 'No accepted jobs')):
            with self.subTest(change=change):
                adapter = lc.FakeInfrastructure({**self.s, **change})
                before = adapter.observe()
                with self.assertRaisesRegex(lc.ContractError, expected):
                    adapter.complete_job(self.s['fence'], 'job-001', {'sequence': 2})
                self.assertEqual(before, adapter.observe())
                self.assertTrue(adapter.lock.acquire(blocking=False), 'Rejected checkpoint retained the lock')
                adapter.lock.release()

    def test_unconfirmed_commits_block_pause_and_destroy_without_changes(self):
        self.s['durableCommitsConfirmed'] = False
        for state in ('PAUSED', 'DESTROYED'):
            m = desired(self.m, state)
            adapter = lc.FakeInfrastructure(self.s)
            with self.assertRaisesRegex(lc.ContractError, 'Durable commits'):
                lc.reconcile_fake(m, adapter, authorization(m) if state == 'DESTROYED' else None)
            self.assertEqual(self.s, adapter.observe())
            self.assertEqual([], adapter.calls)

    def test_stopped_compute_cannot_hide_unconfirmed_durability(self):
        for state in ('PAUSED', 'DESTROYED'):
            for jobs, captures in ((1, 1), (1, 0), (0, 1), (0, 0)):
                for drain_seconds in (5, 31):
                    with self.subTest(state=state, jobs=jobs, captures=captures, drain_seconds=drain_seconds):
                        s = deepcopy(self.s)
                        s['resources'][self.m['resources']['compute']]['state'] = 'STOPPED'
                        s.update(admissionOpen=False, streamsOpen=False, durableCommitsConfirmed=False,
                                 activeJobs=jobs, inflightCaptures=captures, drainSeconds=drain_seconds)
                        m = desired(self.m, state)
                        auth = authorization(m) if state == 'DESTROYED' else None
                        adapter = lc.FakeInfrastructure(s)
                        with self.assertRaisesRegex(lc.ContractError, 'Durable commits'):
                            lc.plan(m, s, auth)
                        with self.assertRaisesRegex(lc.ContractError, 'Durable commits'):
                            lc.reconcile_fake(m, adapter, auth)
                        self.assertEqual(s, adapter.observe())
                        self.assertEqual([], adapter.calls)

    def test_lost_durability_evidence_between_actions_preserves_outstanding_work(self):
        adapter = lc.FakeInfrastructure(self.s)
        perform = adapter.perform
        def lose_evidence(action, manifest):
            perform(action, manifest)
            if action == 'close_admission':
                adapter.state['durableCommitsConfirmed'] = False
        with patch.object(adapter, 'perform', side_effect=lose_evidence):
            result = lc.reconcile_fake(self.m, adapter)
        self.assertEqual('FAILED', result['outcome'])
        self.assertEqual(['close_admission'], adapter.calls)
        for key in ('activeJobs', 'inflightCaptures', 'captures', 'checkpoints', 'fence'):
            self.assertEqual(self.s[key], adapter.state[key])
        self.assertEqual('FAILED', adapter.state['operations'][self.m['operationId']]['status'])

    def test_storage_readiness_and_each_smoke_gate_fail_closed_and_retry(self):
        for gate in ('storageReady', 'authenticatedCaptureRecall', 'isolation'):
            s = self.paused()
            if gate == 'storageReady':
                s[gate] = False
            else:
                s['readiness'][gate] = False
            adapter = lc.FakeInfrastructure(s)
            m = desired(self.m, 'RUNNING')
            result = lc.reconcile_fake(m, adapter)
            self.assertEqual('FAILED', result['outcome'])
            self.assertFalse(adapter.state['admissionOpen'])
            self.assertFalse(adapter.state['streamsOpen'])
            self.assertNotIn('open_admission', adapter.calls)
            if gate == 'storageReady':
                adapter.state[gate] = True
                self.assertNotIn('start_exact_release', adapter.calls)
            else:
                adapter.state['readiness'][gate] = True
            self.assertEqual('SUCCEEDED', lc.reconcile_fake(m, adapter)['outcome'])
            self.assertIsNone(adapter.state['lastFailure'])

    def test_paused_release_change_does_not_wake_compute(self):
        s = self.paused()
        m = desired(self.m, 'PAUSED')
        m['release']['digest'] = 'sha256:' + 'b' * 64
        self.assertEqual([], lc.plan(m, s)['steps'])
        adapter = lc.FakeInfrastructure(s)
        lc.reconcile_fake(m, adapter)
        self.assertEqual(s['release'], adapter.state['release'])
        resume = desired(m, 'RUNNING')
        lc.reconcile_fake(resume, adapter)
        self.assertEqual(m['release'], adapter.state['release'])

    def test_schema_mismatch_and_active_release_change_are_blocked(self):
        m = desired(self.m, 'RUNNING')
        m['release']['schemaVersion'] = 2
        with self.assertRaisesRegex(lc.ContractError, 'Schema migration'):
            lc.plan(m, self.paused())
        m['release']['schemaVersion'] = 1
        m['release']['digest'] = 'sha256:' + 'b' * 64
        with self.assertRaisesRegex(lc.ContractError, 'Pause before'):
            lc.plan(m, self.s)

    def test_absent_resources_never_provision(self):
        for kind in ('compute', 'storage'):
            s = self.paused()
            s['resources'][self.m['resources'][kind]]['state'] = 'ABSENT'
            for state in ('RUNNING', 'PAUSED'):
                with self.assertRaisesRegex(lc.ContractError, 'absent resources'):
                    lc.plan(desired(self.m, state), s)

    def test_retired_environment_cannot_resume(self):
        m = desired(self.m, 'DESTROYED')
        adapter = lc.FakeInfrastructure(self.s)
        lc.reconcile_fake(m, adapter, authorization(m))
        self.assertEqual('DESTROYED', lc.observed_state(m, adapter.state))
        with self.assertRaisesRegex(lc.ContractError, 'Resume cannot provision'):
            lc.plan(desired(self.m, 'RUNNING'), adapter.observe())

    def test_destroy_requires_separate_exact_authority(self):
        m = desired(self.m, 'DESTROYED')
        with self.assertRaisesRegex(lc.ContractError, 'separate exact-target'):
            lc.plan(m, self.s)
        for field in ('operationId', 'deploymentId', 'workspaceId', 'resources', 'backupRef', 'dataPolicy'):
            auth = authorization(m)
            auth[field] = {} if field == 'resources' else 'wrong'
            with self.subTest(field=field), self.assertRaises(lc.ContractError):
                lc.plan(m, self.s, auth)
        with self.assertRaises(lc.ContractError):
            lc.plan(self.m, self.s, authorization(self.m))

    def test_destroy_retention_requires_matching_verified_backup_and_preserves_it(self):
        m = desired(self.m, 'DESTROYED')
        m['backupRef'] = 'fake:backup:001'
        auth = authorization(m, 'retain-backup')
        with self.assertRaisesRegex(lc.ContractError, 'verified backup'):
            lc.plan(m, self.s, auth)
        backup = {'deploymentId': m['deploymentId'], 'workspaceId': m['workspaceId'],
                  'storageRef': m['resources']['storage'], 'verified': True}
        for field in backup:
            s = deepcopy(self.s)
            s['backups'][m['backupRef']] = {**backup, field: False if field == 'verified' else 'wrong'}
            with self.subTest(field=field), self.assertRaisesRegex(lc.ContractError, 'verified backup'):
                lc.plan(m, s, auth)
        self.s['backups'][m['backupRef']] = backup
        adapter = lc.FakeInfrastructure(self.s)
        result = lc.reconcile_fake(m, adapter, auth)
        self.assertEqual('SUCCEEDED', result['outcome'])
        self.assertEqual(self.s['backups'], adapter.state['backups'])
        self.assertEqual({}, adapter.state['captures'])

    def test_wrong_shared_unknown_or_incomplete_targets_fail_closed(self):
        for field, value in (('deploymentId', 'wrong'), ('workspaceId', 'wrong'), ('shared', True), ('kind', 'storage')):
            s = deepcopy(self.s)
            resource = s['resources'][self.m['resources']['compute']]
            resource[field] = value
            if field == 'kind':
                resource['state'] = 'PRESENT'
            with self.subTest(field=field), self.assertRaises(lc.ContractError):
                lc.plan(self.m, s)
        s = deepcopy(self.s)
        del s['resources'][self.m['resources']['compute']]
        with self.assertRaisesRegex(lc.ContractError, 'Missing resource'):
            lc.plan(self.m, s)
        s = deepcopy(self.s)
        s['resources']['extra-owned-resource'] = deepcopy(next(iter(s['resources'].values())))
        with self.assertRaisesRegex(lc.ContractError, 'exact owned resource set'):
            lc.plan(self.m, s)

    def test_unrelated_resources_never_change(self):
        self.s['resources']['unrelated'] = {'kind': 'storage', 'deploymentId': 'other',
                                          'workspaceId': 'other', 'shared': False, 'state': 'PRESENT'}
        adapter = lc.FakeInfrastructure(self.s)
        for state in ('PAUSED', 'RUNNING', 'DESTROYED'):
            m = desired(self.m, state)
            lc.reconcile_fake(m, adapter, authorization(m) if state == 'DESTROYED' else None)
            self.assertEqual(self.s['resources']['unrelated'], adapter.state['resources']['unrelated'])

    def test_stale_and_tampered_plans_fail_before_mutation(self):
        for change in ('observation', 'plan'):
            p = lc.plan(self.m, self.s)
            adapter = lc.FakeInfrastructure(self.s)
            if change == 'observation':
                adapter.state['activeJobs'] += 1
            else:
                p['steps'] = ['delete_storage']
            before = adapter.observe()
            with self.assertRaisesRegex(lc.ContractError, 'Stale or altered'):
                lc.reconcile_fake(self.m, adapter, expected_plan=p)
            self.assertEqual(before, adapter.observe())
            self.assertEqual([], adapter.calls)

    def test_operation_id_cannot_be_reused_for_changed_intent(self):
        s = self.paused()
        for key, value in (('desiredState', 'RUNNING'), ('drainDeadlineSeconds', 50)):
            m = deepcopy(self.m)
            m[key] = value
            with self.assertRaisesRegex(lc.ContractError, 'reused with different intent'):
                lc.plan(m, s)

    def test_lock_and_interrupted_operation_block_competing_writers(self):
        adapter = lc.FakeInfrastructure(self.s)
        with adapter.lock:
            with self.assertRaisesRegex(lc.ContractError, 'locked'):
                lc.reconcile_fake(self.m, adapter)
        lc.reconcile_fake(self.m, adapter, interrupt_after=1)
        with self.assertRaisesRegex(lc.ContractError, 'Another operation'):
            lc.reconcile_fake(desired(self.m, 'DESTROYED'), adapter, authorization(desired(self.m, 'DESTROYED')))
        self.assertEqual('SUCCEEDED', lc.reconcile_fake(self.m, adapter)['outcome'])

    def test_non_fake_adapter_is_rejected_without_observation(self):
        class NotFake:
            def observe(self):
                raise AssertionError('Must not call real adapter')
        with self.assertRaisesRegex(lc.ContractError, 'restricted'):
            lc.reconcile_fake(self.m, NotFake())

    def test_manifest_rejects_unknown_fields_mutable_releases_and_wrong_types(self):
        changes = ({'adapter': 'aws'}, {'topology': 'shared'}, {'desiredState': 'PAUSE'}, {'version': True},
                   {'drainDeadlineSeconds': 0}, {'drainDeadlineSeconds': True}, {'secret': 'forbidden'},
                   {'release': {'digest': 'latest', 'schemaVersion': 1}})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(lc.ContractError):
                lc.validate_manifest({**self.m, **change})

    def test_deadline_equal_to_limit_succeeds(self):
        self.s['drainSeconds'] = self.m['drainDeadlineSeconds']
        self.assertEqual('SUCCEEDED', lc.reconcile_fake(self.m, lc.FakeInfrastructure(self.s))['outcome'])

    def test_destroy_timeout_stops_compute_but_keeps_durable_data_and_resources(self):
        self.s['drainSeconds'] = 31
        m = desired(self.m, 'DESTROYED')
        adapter = lc.FakeInfrastructure(self.s)
        result = lc.reconcile_fake(m, adapter, authorization(m))
        self.assertEqual('RECOVERY_REQUIRED', result['outcome'])
        self.assertNotIn('delete_compute', result['plan']['steps'])
        self.assertNotIn('delete_compute', adapter.calls)
        self.assertNotIn('delete_storage', adapter.calls)
        self.assertEqual('STOPPED', adapter.state['resources'][m['resources']['compute']]['state'])
        self.assertEqual('PRESENT', adapter.state['resources'][m['resources']['storage']]['state'])
        self.assertEqual(self.s['captures'], adapter.state['captures'])
        self.assertEqual(self.s['checkpoints'], adapter.state['checkpoints'])
        self.assertEqual('RECOVERY_REQUIRED', lc.reconcile_fake(m, adapter, authorization(m))['outcome'])

    def test_action_committed_but_journal_ack_lost_still_converges(self):
        for state in ('PAUSED', 'RUNNING', 'DESTROYED'):
            m = desired(self.m, state)
            initial = self.paused() if state == 'RUNNING' else self.s
            auth = authorization(m) if state == 'DESTROYED' else None
            baseline = lc.reconcile_fake(m, lc.FakeInfrastructure(initial), auth)['observed']
            for stop in range(1, len(lc.plan(m, initial, auth)['steps']) + 1):
                with self.subTest(state=state, stop=stop):
                    adapter = lc.FakeInfrastructure(initial)
                    lc.reconcile_fake(m, adapter, auth, interrupt_after=stop)
                    adapter.state['operations'][m['operationId']]['steps'].pop()
                    result = lc.reconcile_fake(m, adapter, auth)
                    self.assertEqual('SUCCEEDED', result['outcome'])
                    for key in ('resources', 'captures', 'fence', 'workersFenced', 'release'):
                        self.assertEqual(baseline[key], adapter.state[key])

    def test_no_network_or_child_process_in_engine(self):
        with patch('socket.socket', side_effect=AssertionError('network')), \
             patch('subprocess.Popen', side_effect=AssertionError('process')):
            adapter = lc.FakeInfrastructure(self.s)
            for state in ('PAUSED', 'RUNNING', 'DESTROYED'):
                m = desired(self.m, state)
                lc.reconcile_fake(m, adapter, authorization(m) if state == 'DESTROYED' else None)


class CliContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.m, self.s = fixtures()
        self.manifest = self.root / 'manifest.json'
        self.observed = self.root / 'observed.json'
        self.write()

    def write(self):
        self.manifest.write_text(json.dumps(self.m))
        self.observed.write_text(json.dumps(self.s))

    def cli(self, command, *args):
        before = {p: p.read_bytes() for p in self.root.iterdir()}
        completed = subprocess.run([sys.executable, str(HERE / 'lifecycle.py'), command,
                                    '--manifest', str(self.manifest), '--observed', str(self.observed), *args],
                                   capture_output=True, text=True)
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.iterdir()})
        self.assertNotIn('Traceback', completed.stderr)
        return completed

    def test_documented_status_plan_pause_resume_destroy_flow(self):
        for command in ('status', 'plan'):
            result = self.cli(command)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('local-fake', json.loads(result.stdout)['mode'])
        for state in ('PAUSED', 'RUNNING', 'DESTROYED'):
            self.m = desired(self.m, state)
            self.write()
            args = ['--dry-run']
            if state == 'DESTROYED':
                auth = self.root / 'authorization.json'
                auth.write_text(json.dumps(authorization(self.m)))
                args += ['--destroy-authorization', str(auth)]
            result = self.cli('reconcile', *args)
            self.assertEqual(0, result.returncode, result.stderr)
            output = json.loads(result.stdout)
            self.assertEqual('SUCCEEDED', output['outcome'])
            self.s = output['observed']
            self.assertEqual(state, lc.observed_state(self.m, self.s))

    def test_status_can_inspect_destroy_intent_without_authorizing_it(self):
        self.m = desired(self.m, 'DESTROYED')
        self.write()
        self.assertEqual(0, self.cli('status').returncode)
        self.assertEqual(2, self.cli('plan').returncode)
        self.assertEqual(2, self.cli('reconcile', '--dry-run').returncode)

    def test_stopped_unconfirmed_work_is_inspectable_but_cannot_reconcile(self):
        self.s['resources'][self.m['resources']['compute']]['state'] = 'STOPPED'
        self.s.update(admissionOpen=False, streamsOpen=False, durableCommitsConfirmed=False)
        self.write()
        status = self.cli('status')
        self.assertEqual(0, status.returncode, status.stderr)
        self.assertEqual(self.s, json.loads(status.stdout)['observed'])
        for command, args in (('plan', ()), ('reconcile', ('--dry-run',))):
            result = self.cli(command, *args)
            self.assertEqual(2, result.returncode, result.stdout)
            self.assertIn('Durable commits', json.loads(result.stderr)['error'])
        # The same stopped fixture can reconcile once its injected evidence is confirmed.
        self.s['durableCommitsConfirmed'] = True
        self.write()
        result = self.cli('reconcile', '--dry-run')
        self.assertEqual(0, result.returncode, result.stderr)
        observed = json.loads(result.stdout)['observed']
        self.assertEqual('PAUSED', lc.observed_state(self.m, observed))
        self.assertEqual(self.s['captures'], observed['captures'])
        self.assertEqual(self.s['checkpoints'], observed['checkpoints'])

    def test_partial_inventory_is_reported_as_incomplete(self):
        self.s['resources'][self.m['resources']['storage']]['state'] = 'ABSENT'
        self.write()
        result = self.cli('status')
        self.assertEqual('INCOMPLETE', json.loads(result.stdout)['observedState'])

    def test_apply_missing_dry_run_and_wrong_adapter_fail(self):
        for command, args in (('reconcile', ()), ('reconcile', ('--apply',)), ('provision', ())):
            self.assertEqual(2, self.cli(command, *args).returncode)
        self.m['adapter'] = 'aws'
        self.write()
        self.assertEqual(2, self.cli('plan').returncode)

    def test_failure_and_timeout_have_nonzero_exit_and_structured_state(self):
        self.s['drainSeconds'] = 60
        self.write()
        result = self.cli('reconcile', '--dry-run')
        self.assertEqual(3, result.returncode)
        self.assertEqual('RECOVERY_REQUIRED', json.loads(result.stdout)['outcome'])
        self.s = lc.reconcile_fake(fixtures()[0], lc.FakeInfrastructure(fixtures()[1]))['observed']
        self.s['storageReady'] = False
        self.m = desired(self.m, 'RUNNING')
        self.write()
        result = self.cli('reconcile', '--dry-run')
        self.assertEqual(3, result.returncode)
        self.assertEqual('FAILED', json.loads(result.stdout)['outcome'])

    def test_invalid_json_duplicate_keys_and_missing_observations_fail(self):
        for invalid in ('{', '{"version":1,"version":2}', '{"version":NaN}', 'null', '[]'):
            self.manifest.write_text(invalid)
            self.assertEqual(2, self.cli('plan').returncode)
        self.write()
        self.observed.unlink()
        self.assertEqual(2, self.cli('plan').returncode)


if __name__ == '__main__':
    unittest.main()
