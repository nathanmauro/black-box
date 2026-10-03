# Keep Evidence PostgreSQL verification portable and scoped

## Reproduced contract mismatch

The PostgreSQL guide uses an explicit loopback JDBC URL on port 5432 and says those fixture
variables enable the same database checks in the full local verification gate. The Evidence
consumer fixture instead permits 5432 only when CI=true, and otherwise hardcodes 18877. A
read-only guard reproduction rejected both documented local 5432 and another explicit port
before any connection. The original targeted two-class documentation command is unaffected;
full `mvn test` and `scripts/verify.sh` include Evidence and encounter the mismatch.

## Safety contract

Change only the Evidence test fixture and its guard tests. Require an explicit JDBC PostgreSQL
URL with host 127.0.0.1 or localhost, database blackbox_test, username blackbox_test, and a port
from 1 through 65535. Reject URL user information, query parameters and fragments. Before any
owned schema creation or deletion, verify the observed database/user and server port match the
validated configuration. If an expected data directory is provided, require an exact match too.
A forwarded connection whose server port differs from the URL fails closed. The test creates
and drops only its own random bb_evidence schema; this does not authorize a live database.

No production or persistence code, other PostgreSQL fixtures, CI workflow, credentials, service
configuration, or provider behavior changes. Root provisions and owns the disposable server,
Git integration and publication. The worker must leave that server running for coordinator
cleanup and close its own app contexts/connections.

## Verification

First run the existing Evidence PostgreSQL class on the explicitly authorized alternate-port
fixture and observe setup rejection. Offline controls must accept valid explicit ports without
CI-dependent selection and reject invalid targets/identities. Then run all 28 actual Evidence
HTTP/MCP cases without skips, retain the 28 SQLite controls, and prove a wrong expected directory
fails before creating a schema. Apply/check the scoped pinned Java formatter and whitespace.
Record actual results and limitations before source freeze.

## Results

The unmodified PostgreSQL class aborted in its setup method on the authorized alternate-port
fixture: expected 18877, observed URL port 18878. Surefire reported one setup error; none of the
28 inherited consumer cases could run. This reproduced the local portability regression without
changing the database.

After the repair, all 77 targeted cases passed with zero failures, errors or skips: 21 offline
guard cases, 28 PostgreSQL HTTP/MCP consumer cases on the alternate port, and the same 28 SQLite
controls. The PostgreSQL run verified the exact server identity and expected data directory.
A separate wrong-directory run failed during connection verification before schema creation.
The bb_evidence schema inventory was empty before testing, after that rejection, and after the
successful consumer suite. The fake identity tests also reject mismatched database/user/server
port; their checks perform no SQL mutations.

Scoped Palantir apply/check and the whitespace check passed. An independent read-only review found
no remaining issue. No full backend or browser suite was repeated for this test-fixture-only
change. All worker app contexts/connections were closed; the coordinator-owned disposable server
was intentionally left running for coordinator cleanup. No production, CI workflow, service
configuration, provider, Git history or publication was changed by the worker. Source is ready
for coordinator review and integration.
