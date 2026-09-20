"""Miniature continuation repositories. Expected values never enter model workspaces."""

import random
import textwrap

TASKS = ("export-checkpoint", "webhook-ledger", "invoice-adjustments",
         "config-inheritance", "migration-routing")


def code(value):
    return textwrap.dedent(value).lstrip()


def case(name, payload, expected):
    return {"name": name, "args": [payload], "expected": expected}


def fixture(name, seed, continuation=True):
    token = str(random.Random(seed).randrange(10000, 99999))
    if name == "export-checkpoint":
        protocol = """The export protocol uses pages containing cursor, items, and next_cursor.
The initial cursor is null; strings including empty string and '0' are valid opaque cursors.
Only next_cursor=null means the chain ended. Empty pages can have a continuation.
Rows have tenant, id, revision, and kind. Their identity is (tenant,id,revision).
Keep the first matching row for each identity, in encounter order. Nonmatching rows do
not mark an identity seen. Never mutate inputs. All cursor chains are valid and finite.
Legacy op=scan consumes the entire chain and returns a list of matching rows.
"""
        current = """Add op=resume while preserving scan. resume receives page_budget >= 0 and
optional state={cursor,seen,done}; default is {cursor:null,seen:[],done:false}.
seen is an ordered list of [tenant,id,revision] identities already emitted. Process at most
page_budget pages, regardless of how many matches a page contains. Return
{rows:[new matches],state:{cursor:next unprocessed cursor,seen:all emitted identities,done:bool}}.
Advance state only after processing a whole page. A terminal page sets cursor=null and done=true.
A zero budget or already-done state returns no rows and preserves state, without reading pages.
The page list may include only the unprocessed suffix. Preserve the order of existing seen entries.
"""
        starter = code('''
            def run(data):
                pages = {p['cursor']: p for p in data['pages']}
                cursor, rows, seen = None, [], set()
                while True:
                    page = pages[cursor]
                    for row in page['items']:
                        if row['id'] not in seen:
                            seen.add(row['id'])
                            if row['kind'] == data['kind']:
                                rows.append(row)
                    if not page['items'] or not page['next_cursor']:
                        return rows
                    cursor = page['next_cursor']
        ''')
        prior_ref = code('''
            def run(data):
                pages = {p['cursor']: p for p in data['pages']}
                cursor, rows, seen = None, [], set()
                while True:
                    page = pages[cursor]
                    for row in page['items']:
                        key = (row['tenant'], row['id'], row['revision'])
                        if row['kind'] == data['kind'] and key not in seen:
                            seen.add(key)
                            rows.append(row)
                    cursor = page['next_cursor']
                    if cursor is None:
                        return rows
        ''')
        ref = code('''
            import copy
            def run(data):
                resume = data['op'] == 'resume'
                state = copy.deepcopy(data.get('state', {'cursor':None,'seen':[],'done':False})) if resume else {'cursor':None,'seen':[],'done':False}
                rows, seen = [], {tuple(x) for x in state['seen']}
                budget = data['page_budget'] if resume else len(data['pages'])
                pages = {p['cursor']:p for p in data['pages']}
                while budget > 0 and not state['done']:
                    page = pages[state['cursor']]
                    for row in page['items']:
                        key = (row['tenant'],row['id'],row['revision'])
                        if row['kind'] == data['kind'] and key not in seen:
                            rows.append(row)
                            seen.add(key)
                            state['seen'].append(list(key))
                    state['cursor'] = page['next_cursor']
                    state['done'] = state['cursor'] is None
                    budget -= 1
                return {'rows':rows,'state':state} if resume else rows
        ''')
        a = dict(tenant="a", id=token, revision=1, kind="wanted")
        b = dict(a, tenant="b")
        c = dict(a, revision=2)
        noise = dict(a, kind="noise")
        key = lambda row: [row[k] for k in ("tenant", "id", "revision")]
        pages = [{"cursor": None, "items": [noise], "next_cursor": ""},
                 {"cursor": "", "items": [], "next_cursor": "0"},
                 {"cursor": "0", "items": [a, b, a, c], "next_cursor": None}]
        basic = {"op": "scan", "kind": "wanted", "pages": [{"cursor": None, "items": [a], "next_cursor": None}]}
        prior = [case("basic-scan", basic, [a]), case("opaque-empty-and-composite", dict(basic, pages=pages), [a, b, c])]
        cases = prior + [
            case("checkpoint-on-empty-string", dict(basic, op="resume", pages=pages, page_budget=1),
                 {"rows": [], "state": {"cursor": "", "seen": [], "done": False}}),
            case("empty-page-consumes-budget", dict(basic, op="resume", pages=pages, page_budget=2),
                 {"rows": [], "state": {"cursor": "0", "seen": [], "done": False}}),
            case("resume-preserves-seen", dict(basic, op="resume", pages=pages[2:], page_budget=4,
                 state={"cursor": "0", "seen": [key(a)], "done": False}),
                 {"rows": [b, c], "state": {"cursor": None, "seen": [key(a), key(b), key(c)], "done": True}}),
            case("zero-budget-no-pages", dict(basic, op="resume", pages=[], page_budget=0),
                 {"rows": [], "state": {"cursor": None, "seen": [], "done": False}}),
            case("already-done", dict(basic, op="resume", pages=[], page_budget=8,
                 state={"cursor": None, "seen": [key(a)], "done": True}),
                 {"rows": [], "state": {"cursor": None, "seen": [key(a)], "done": True}})]
    elif name == "webhook-ledger":
        protocol = """Ledger events have tenant, event_id, account, seq (integer), and balance
(integer snapshot, NOT a delta). Deduplicate by (tenant,event_id), first occurrence wins even
if that event is too old to update the balance. Accounts are identified by (tenant,account).
Within each account greatest seq wins; equal seq keeps the first value. Legacy op=fold returns
a list of {tenant,account,seq,balance}, ordered lexicographically by tenant then account.
No mutation. Negative balances and seq=0 are valid. Empty events return [].
"""
        current = """CURRENT CONTRACT v2 changes event identity from the historical globally unique
event_id to the PAIR (tenant,event_id). This applies to fold and resume. The current protocol
document reflects v2; old handoff claims about global event IDs are obsolete.
Add op=resume. Input state contains accounts (fold output) and seen
(ordered [tenant,event_id] pairs). Default both lists empty. Fold new events using the same rules;
return {accounts:sorted account snapshots,seen:all first-seen event keys in encounter order}.
New behavior: an event may have deleted=true and no balance. Its seq participates in the same
ordering; store {tenant,account,seq,deleted:true} as a tombstone. A lower/equal seq cannot resurrect
it. A greater seq normal event restores the account and removes deleted. Tombstones remain in
state.accounts to reject stale replay. This extension also applies to op=fold.
"""
        starter = code('''
            def run(data):
                accounts = {}
                seen = set()
                for e in data['events']:
                    if e['event_id'] in seen:
                        continue
                    seen.add(e['event_id'])
                    accounts[e['account']] = {k:e[k] for k in ('tenant','account','seq','balance')}
                return list(accounts.values())
        ''')
        prior_ref = code('''
            def run(data):
                accounts, seen = {}, set()
                for e in data['events']:
                    key = (e['tenant'],e['event_id'])
                    if key in seen: continue
                    seen.add(key)
                    account = (e['tenant'],e['account'])
                    if account not in accounts or e['seq'] > accounts[account]['seq']:
                        accounts[account] = {k:e[k] for k in ('tenant','account','seq','balance')}
                return [accounts[k] for k in sorted(accounts)]
        ''')
        prior_ref = prior_ref.replace("key = (e['tenant'],e['event_id'])", "key = e['event_id']")
        if not continuation:
            protocol = protocol.replace("Deduplicate by (tenant,event_id)", "CONTRACT v1: Deduplicate by globally unique event_id")
        ref = code('''
            import copy
            def run(data):
                state = copy.deepcopy(data.get('state', {'accounts':[],'seen':[]})) if data['op']=='resume' else {'accounts':[],'seen':[]}
                accounts = {(e['tenant'],e['account']):e for e in state['accounts']}
                seen = {tuple(x) for x in state['seen']}
                for e in data['events']:
                    key = (e['tenant'],e['event_id'])
                    if key in seen: continue
                    seen.add(key)
                    state['seen'].append(list(key))
                    account = (e['tenant'],e['account'])
                    if account not in accounts or e['seq'] > accounts[account]['seq']:
                        value = {k:e[k] for k in ('tenant','account','seq')}
                        value.update({'deleted':True} if e.get('deleted') else {'balance':e['balance']})
                        accounts[account] = value
                state['accounts'] = [accounts[k] for k in sorted(accounts)]
                return state if data['op']=='resume' else state['accounts']
        ''')
        e = dict(tenant="a", event_id=token, account="cash", seq=2, balance=10)
        snap = lambda x: {k:x[k] for k in ("tenant", "account", "seq", "balance")}
        f = dict(e, tenant="b", balance=-5)
        old = dict(e, event_id="old", seq=0, balance=99)
        common = [case("single-account", {"op":"fold","events":[e]}, [snap(e)]),
                  case("sequence-and-ties", {"op":"fold","events":[e,old,dict(e,event_id="tie",balance=33)]}, [snap(e)])]
        prior = common + [case("v1-global-event-id", {"op":"fold","events":[f,e]}, [snap(f)])]
        tomb = {"tenant":"a","account":"cash","seq":3,"deleted":True}
        state = {"accounts":[tomb],"seen":[["a",token]]}
        cases = common + [
            case("v2-scoped-event-id", {"op":"fold","events":[f,e]}, [snap(e),snap(f)]),
            case("seen-survives-resume", {"op":"resume","state":{"accounts":[snap(e)],"seen":[["a",token]]},"events":[dict(e,seq=8,balance=88),f]},
                 {"accounts":[snap(e),snap(f)],"seen":[["a",token],["b",token]]}),
            case("tombstone-blocks-stale", {"op":"resume","state":state,"events":[old,dict(e,event_id="equal",seq=3)]},
                 {"accounts":[tomb],"seen":[["a",token],["a","old"],["a","equal"]]}),
            case("resurrection", {"op":"resume","state":state,"events":[dict(e,event_id="new",seq=4,balance=0)]},
                 {"accounts":[dict(snap(e),seq=4,balance=0)],"seen":[["a",token],["a","new"]]}),
            case("deletion-without-balance", {"op":"fold","events":[e,dict(tomb,event_id="delete"),old]}, [tomb]),
            case("stale-event-still-seen", {"op":"resume","state":{"accounts":[snap(e)],"seen":[]},"events":[old,dict(old,seq=9)]},
                 {"accounts":[snap(e)],"seen":[["a","old"]]}),
            case("empty-state", {"op":"resume","events":[]}, {"accounts":[],"seen":[]})]
    elif name == "invoice-adjustments":
        protocol = """Legacy op=invoice returns a fixed two-decimal string. Lines have id,
unit_price (decimal string), quantity (integer). Multiply exactly, round EACH LINE to cents
with ROUND_HALF_UP, then sum. Negative credits use the same rule. No mutation, no binary floats.
Empty total is '0.00', never '-0.00'. Decimal prices have at most 12 digits before the decimal.
"""
        current = """Add op=adjust with lines and adjustments. First compute each rounded line
amount in integer cents. An adjustment has key (unique identity; first occurrence wins),
line_id, and percent (signed decimal string). Each adjustment applies to the ORIGINAL rounded
amount of its target line, not a running discounted amount. Compute cents*percent/100 and round
to an integer with ROUND_HALF_UP, then subtract that adjustment from the line. Negative percent
is a surcharge. Unknown line IDs are ignored but still consume the adjustment key.
Return {lines:[{id,amount:two-decimal-string}] in original line order,total:two-decimal-string}.
Line IDs are unique. Preserve legacy invoice behavior. Normalize negative zero in all outputs.
"""
        starter = "def run(data):\n    return f\"{sum(float(x['unit_price'])*x['quantity'] for x in data['lines']):.2f}\"\n"
        prior_ref = code('''
            from decimal import Decimal, ROUND_HALF_UP
            def run(data):
                total = sum((Decimal(x['unit_price'])*x['quantity']).quantize(Decimal('.01'),rounding=ROUND_HALF_UP) for x in data['lines'])
                return '0.00' if total == 0 else format(total,'.2f')
        ''')
        ref = code('''
            from decimal import Decimal, ROUND_HALF_UP
            def run(data):
                def rounded(v): return int(v.quantize(Decimal('1'), rounding=ROUND_HALF_UP))
                def money(v): return ('-' if v < 0 else '') + str(abs(v)//100) + '.' + str(abs(v)%100).zfill(2)
                original = {x['id']:rounded(Decimal(x['unit_price'])*x['quantity']*100) for x in data['lines']}
                amounts = dict(original)
                if data['op'] == 'adjust':
                    seen = set()
                    for a in data['adjustments']:
                        if a['key'] in seen: continue
                        seen.add(a['key'])
                        if a['line_id'] in amounts:
                            amounts[a['line_id']] -= rounded(Decimal(original[a['line_id']])*Decimal(a['percent'])/100)
                total = money(sum(amounts.values()))
                return {'lines':[{'id':x['id'],'amount':money(amounts[x['id']])} for x in data['lines']],'total':total} if data['op']=='adjust' else total
        ''')
        line = {"id":token,"unit_price":"1.005","quantity":1}
        credit = {"id":"credit","unit_price":"-1.005","quantity":1}
        prior = [case("basic-invoice", {"op":"invoice","lines":[dict(line,unit_price="2.00",quantity=3)]}, "6.00"),
                 case("line-rounding", {"op":"invoice","lines":[line,dict(line,id="second")]}, "2.02"),
                 case("credit-rounding", {"op":"invoice","lines":[credit]}, "-1.01")]
        a = {"key":"one","line_id":token,"percent":"50"}
        cases = prior + [
            case("original-basis-not-compound", {"op":"adjust","lines":[line],"adjustments":[a,dict(a,key="two")]}, {"lines":[{"id":token,"amount":"-0.01"}],"total":"-0.01"}),
            case("duplicate-key-first", {"op":"adjust","lines":[line],"adjustments":[a,dict(a,percent="100")]}, {"lines":[{"id":token,"amount":"0.50"}],"total":"0.50"}),
            case("unknown-consumes-key", {"op":"adjust","lines":[line],"adjustments":[dict(a,line_id="missing"),a]}, {"lines":[{"id":token,"amount":"1.01"}],"total":"1.01"}),
            case("negative-credit-discount", {"op":"adjust","lines":[credit],"adjustments":[dict(a,line_id="credit")]}, {"lines":[{"id":"credit","amount":"-0.50"}],"total":"-0.50"}),
            case("negative-surcharge", {"op":"adjust","lines":[line],"adjustments":[dict(a,percent="-50")]}, {"lines":[{"id":token,"amount":"1.52"}],"total":"1.52"}),
            case("zero-and-order", {"op":"adjust","lines":[line,credit],"adjustments":[]}, {"lines":[{"id":token,"amount":"1.01"},{"id":"credit","amount":"-1.01"}],"total":"0.00"}),
            case("empty-adjustment", {"op":"adjust","lines":[],"adjustments":[a]}, {"lines":[],"total":"0.00"})]
    elif name == "config-inheritance":
        protocol = """Legacy op=merge receives layers, returns their recursive left-to-right
merge. Dictionaries merge recursively; arrays/scalars replace, never concatenate. Null is
a real value (not missing). A dictionary exactly equal to {'$delete':true} deletes its key.
Deletion of an absent key is harmless. Delete markers nested in newly introduced dictionaries
also apply. A dictionary with $delete plus other keys is ordinary data. Never mutate inputs.
"""
        current = """Add op=resolve with profiles map and name. Each profile has extends (ordered
list of parent names, default []) and values (dictionary, default {}). Recursively apply ancestors
then the profile's values. Apply each profile once PER PATH, not once globally: shared ancestors
are deliberately reapplied in a diamond. Parent ordering therefore matters. Keep deletion
operations until their position in this traversal; do not merge parents independently and lose
their deletion markers. Ignore any input map insertion order; only extends determines order.
An unknown referenced profile returns {'error':'unknown-profile'}; a reachable cycle returns
{'error':'cycle'}. If both exist in reachable graph, cycle wins. Validate reachable graph before
merging. Unreachable bad profiles do not matter. Success is {'config':merged dictionary}.
"""
        starter = "def run(data):\n    result = {}\n    for layer in data['layers']:\n        result.update(layer)\n    return result\n"
        helper = code('''
            import copy
            def merge(target, layer):
                for key, value in layer.items():
                    if isinstance(value,dict) and value == {'$delete':True}:
                        target.pop(key,None)
                    elif isinstance(value,dict):
                        child = target.get(key)
                        target[key] = merge(child if isinstance(child,dict) else {}, value)
                    else:
                        target[key] = copy.deepcopy(value)
                return target
        ''')
        prior_ref = helper + "\ndef run(data):\n    result = {}\n    for layer in data['layers']: merge(result,layer)\n    return result\n"
        ref = helper + code('''
            def run(data):
                if data['op']=='merge':
                    result = {}
                    for layer in data['layers']: merge(result,layer)
                    return result
                profiles = data['profiles']
                errors, order = set(), []
                def visit(name, path):
                    if name in path:
                        errors.add('cycle'); return
                    if name not in profiles:
                        errors.add('unknown-profile'); return
                    for parent in profiles[name].get('extends',[]): visit(parent,path+[name])
                    order.append(profiles[name].get('values',{}))
                visit(data['name'],[])
                if errors: return {'error':'cycle' if 'cycle' in errors else 'unknown-profile'}
                result = {}
                for layer in order: merge(result,layer)
                return {'config':result}
        ''')
        delete = {"$delete":True}
        prior = [case("basic-merge", {"op":"merge","layers":[{"x":1},{"x":2}]}, {"x":2}),
                 case("nested-delete-null-array", {"op":"merge","layers":[{"a":{"x":1,"y":2},"b":[1]}, {"a":{"x":delete,"z":None},"b":[2],"new":{"gone":delete}}]}, {"a":{"y":2,"z":None},"b":[2],"new":{}})]
        profiles = {"base":{"values":{"x":1,"nested":{"a":1}}},
                    "left":{"extends":["base"],"values":{"x":2}},
                    "right":{"extends":["base"],"values":{"nested":{"a":delete}}},
                    token:{"extends":["left","right"],"values":{"z":None}}}
        cases = prior + [
            case("diamond-reapply-and-delete", {"op":"resolve","profiles":profiles,"name":token}, {"config":{"x":1,"nested":{},"z":None}}),
            case("delete-across-parent-boundary", {"op":"resolve","profiles":{"a":{"values":{"x":1}},"b":{"values":{"x":delete}},"c":{"extends":["a","b"]}},"name":"c"}, {"config":{}}),
            case("cycle-precedes-missing", {"op":"resolve","profiles":{"a":{"extends":["missing","b"]},"b":{"extends":["a"]}},"name":"a"}, {"error":"cycle"}),
            case("unknown-profile", {"op":"resolve","profiles":{},"name":token}, {"error":"unknown-profile"}),
            case("unreachable-cycle", {"op":"resolve","profiles":{"a":{"values":{"x":None}},"b":{"extends":["b"]}},"name":"a"}, {"config":{"x":None}}),
            case("delete-lookalike-is-data", {"op":"merge","layers":[{"a":{"$delete":True,"extra":token}}]}, {"a":{"$delete":True,"extra":token}})]
    elif name == "migration-routing":
        protocol = """Legacy op=route finds a directed migration path. Edges have id (unique
string), src, dst, cost (nonnegative integer), reversible (boolean). Follow src->dst only;
reversible does not add reverse traversal. Choose lowest total cost, then fewest edges,
then lexicographically smallest list of edge IDs. A valid route never repeats a version.
Return {'path':[edge IDs],'cost':integer}, or {'error':'unreachable'}. Same start/target returns
empty path and cost 0. Input order must not decide ties. Never mutate inputs.
"""
        current = """Add op=plan with start,target,edges,blocked (list of version strings), and
require_reversible (bool). Blocked versions cannot be visited, including endpoints; if any endpoint
is blocked return {'error':'blocked-endpoint'} (even when start=target). When require_reversible
is true, only edges with reversible=true may be used. On success return
{'path':[IDs],'cost':n,'rollback':[IDs in reverse order]} if ALL selected edges are reversible,
otherwise rollback=null. Reverse traversal is still not allowed during planning. Preserve legacy
route behavior: it ignores blocked/require_reversible and returns no rollback field.
Graphs have at most 9 versions and 24 edges; exhaustive simple-path search is acceptable.
"""
        starter = code('''
            def run(data):
                current, path, cost = data['start'], [], 0
                seen = set()
                while current != data['target']:
                    if current in seen: return {'error':'unreachable'}
                    seen.add(current)
                    options = [e for e in data['edges'] if e['src']==current]
                    if not options: return {'error':'unreachable'}
                    edge = min(options,key=lambda e:e['cost'])
                    path.append(edge['id']); cost += edge['cost']; current = edge['dst']
                return {'path':path,'cost':cost}
        ''')
        ref = code('''
            def run(data):
                plan = data['op']=='plan'
                blocked = set(data.get('blocked',[])) if plan else set()
                if data['start'] in blocked or data['target'] in blocked: return {'error':'blocked-endpoint'}
                choices = []
                def visit(node, visited, path, cost, reversible):
                    if node == data['target']:
                        choices.append((cost,len(path),path,reversible)); return
                    for e in data['edges']:
                        if e['src'] != node or e['dst'] in visited or e['dst'] in blocked: continue
                        if plan and data.get('require_reversible',False) and not e['reversible']: continue
                        visit(e['dst'],visited|{e['dst']},path+[e['id']],cost+e['cost'],reversible and e['reversible'])
                visit(data['start'],{data['start']},[],0,True)
                if not choices: return {'error':'unreachable'}
                cost, _, path, reversible = min(choices)
                result = {'path':path,'cost':cost}
                if plan: result['rollback'] = list(reversed(path)) if reversible else None
                return result
        ''')
        prior_ref = ref.replace("plan = data['op']=='plan'", "plan = False")
        edge = lambda i,s,d,c,r=True: {"id":i,"src":s,"dst":d,"cost":c,"reversible":r}
        edges = [edge("cheap","a","b",0),edge("expensive","b","z",9),edge(token,"a","z",3,False),
                 edge("rev1","a","c",2),edge("rev2","c","z",2),edge("cycle","b","a",0)]
        prior = [case("direct-route", {"op":"route","start":"a","target":"z","edges":[edge(token,"a","z",2)]}, {"path":[token],"cost":2}),
                 case("global-not-greedy", {"op":"route","start":"a","target":"z","edges":edges}, {"path":[token],"cost":3}),
                 case("tie-by-id", {"op":"route","start":"a","target":"z","edges":[edge("z","a","z",2),edge("a","a","z",2)]}, {"path":["a"],"cost":2})]
        base = {"op":"plan","start":"a","target":"z","edges":edges,"blocked":[],"require_reversible":False}
        cases = prior + [
            case("irreversible-rollback-null", base, {"path":[token],"cost":3,"rollback":None}),
            case("require-reversible", dict(base,require_reversible=True), {"path":["rev1","rev2"],"cost":4,"rollback":["rev2","rev1"]}),
            case("blocked-middle", dict(base,require_reversible=True,blocked=["c"]), {"path":["cheap","expensive"],"cost":9,"rollback":["expensive","cheap"]}),
            case("blocked-endpoint-priority", dict(base,start="z",blocked=["z"]), {"error":"blocked-endpoint"}),
            case("no-reverse-traversal", dict(base,start="z",target="a"), {"error":"unreachable"}),
            case("empty-plan", dict(base,start="z"), {"path":[],"cost":0,"rollback":[]}),
            case("fewest-edges-before-ids", dict(base,edges=[edge("a","a","b",1),edge("b","b","z",1),edge("z","a","z",2)]), {"path":["z"],"cost":2,"rollback":["z"]})]
    else:
        raise ValueError(name)
    chosen = cases if continuation else prior
    return {"name":name,"function":"run","starter":starter,
            "reference":ref if continuation else prior_ref,"cases":chosen,
            "public":[chosen[0]], "files":{
                "README.md": "# Maintenance task\n\nImplement run(data) in solution.py using only the Python standard library.\n"
                             "Read docs/protocol.md and docs/change.md. Preserve existing behavior.\n",
                "docs/protocol.md":protocol,
                "docs/change.md":current if continuation else "Repair legacy behavior to match docs/protocol.md.\n"}}
