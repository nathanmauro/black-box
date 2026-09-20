import copy
import json
from pathlib import Path
import tempfile
import types
import unittest

import continuation as c
from continuation_fixtures import TASKS, fixture
from grade import grade


def module(source):
    result = types.ModuleType("authored_fixture")
    exec(source, result.__dict__)
    return result


class ContinuationTests(unittest.TestCase):
    def test_difficulty_gate_precedes_expensive_evaluation(self):
        for passed in range(6):
            rows=[{'passed':i < passed,'status':'graded'} for i in range(5)]
            self.assertEqual(c.difficulty_gate(rows), 'floor_detected' if passed == 0 else 'ceiling_detected' if passed == 5 else 'evaluation')
        with self.assertRaises(RuntimeError): c.difficulty_gate([])
        with self.assertRaises(RuntimeError): c.difficulty_gate([{'passed':False,'status':'not_run'}]*5)

    def test_authored_contracts_and_unsolved_checkpoints(self):
        for name in TASKS:
            for seed in (1,42,20260918):
                with self.subTest(task=name,seed=seed):
                    prior, current = fixture(name,seed,False), fixture(name,seed,True)
                    self.assertFalse(grade(module(prior['starter']),'run',prior['cases'])['passed'])
                    self.assertTrue(grade(module(prior['reference']),'run',prior['cases'])['passed'])
                    self.assertFalse(grade(module(prior['reference']),'run',current['cases'])['passed'])
                    self.assertTrue(grade(module(current['reference']),'run',current['cases'])['passed'])

    def test_sixty_runs_in_paired_blocks(self):
        rows = c.schedule(3,42)
        self.assertEqual(len(rows),60)
        self.assertEqual(rows,c.schedule(3,42))
        for i in range(0,len(rows),4):
            block=rows[i:i+4]
            self.assertEqual({r['arm'] for r in block},set(c.ARMS))
            self.assertEqual(len({(r['task'],r['repeat'],r['fixture_seed']) for r in block}),1)

    def test_history_controls_do_not_mutate_real_evidence(self):
        original = {'task':TASKS[0],'context':'Actual agent handoff','verification':{'attempts':2}}
        snapshot = copy.deepcopy(original)
        self.assertEqual(c.useful_history('bare',original),[])
        self.assertEqual(c.useful_history('handoff',original),c.useful_history('blackbox',original))
        self.assertNotEqual(c.useful_history('misleading',original),c.useful_history('handoff',original))
        self.assertEqual(original,snapshot)

    def test_workspace_allowlist_excludes_hidden_cases_and_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            f=fixture('config-inheritance',42)
            root=c.workspace(Path(tmp),f,'def run(data): return None\n',[])
            self.assertEqual({str(p.relative_to(root)) for p in root.rglob('*') if p.is_file()},
                             {'README.md','solution.py','test_public.py','docs/protocol.md','docs/change.md','history.json'})
            public=(root/'test_public.py').read_text()
            compile(public,'test_public.py','exec')
            for test in f['cases'][1:]:
                self.assertNotIn(test['name'],public)
            self.assertNotEqual((root/'solution.py').read_text(),f['reference'])

    def test_recall_rejects_missing_or_ambiguous_history(self):
        class Fake:
            def __init__(self,items): self.items=items
            def recall(self,scope): return {'items':self.items}
        with tempfile.TemporaryDirectory() as tmp:
            for items in ([],[{'kind':'decision'}],[{},{}]):
                with self.assertRaises(RuntimeError): c.recall_history(Fake(items),'sample',Path(tmp)/'recall.json')
            expected={'context':'measured example'}
            self.assertEqual(c.recall_history(Fake([{'kind':'handoff','headline':json.dumps(expected)}]),'sample',Path(tmp)/'recall.json'),expected)

    def test_pair_verification_detects_contaminated_inputs(self):
        base={'task':'sample','repeat':0,'arm':'handoff','input_hashes':{'solution.py':'same','history.json':'same'}}
        with tempfile.TemporaryDirectory() as tmp:
            other=dict(base,arm='blackbox')
            c.verify_pairs(Path(tmp),[base,other])
            for field in ('solution.py','history.json'):
                bad=dict(other,input_hashes=dict(other['input_hashes'],**{field:'different'}))
                with self.assertRaises(RuntimeError): c.verify_pairs(Path(tmp),[base,bad])

    def test_unrun_and_infrastructure_rows_not_silently_dropped(self):
        rows=[{'task':'sample','repeat':0,'arm':'bare','status':'graded','passed':True,
               'metrics':{'input_tokens':20},'agent_seconds':1},
              {'task':'sample','repeat':1,'arm':'bare','status':'not_run','passed':False}]
        summary=c.aggregate(rows)['bare']
        self.assertEqual(summary['scheduled'],2)
        self.assertEqual(summary['passed'],1)
        self.assertIsNone(summary['input_tokens'])
        self.assertIsNone(summary['median_agent_seconds'])

    def test_paired_outcomes_separate_timeouts_and_infrastructure(self):
        rows=[]
        for repeat, (a,b,status) in enumerate([(True,True,'graded'),(True,False,'timeout'),
                                              (False,True,'graded'),(False,False,'graded'),
                                              (True,False,'infrastructure_error')]):
            rows += [dict(task='sample',repeat=repeat,arm='bare',passed=a,status='graded'),
                     dict(task='sample',repeat=repeat,arm='handoff',passed=b,status=status)]
        self.assertEqual(c.paired(rows)['handoff'],dict(both_pass=1,bare_only=1,memory_only=1,both_fail=1,incomplete=1))


if __name__ == '__main__': unittest.main()
