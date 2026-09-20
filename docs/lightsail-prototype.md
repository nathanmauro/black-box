# Shared managed-container prototype

This deployment design runs Black Box as one shared HTTPS application in Lightsail Containers, with canonical data in
managed PostgreSQL. Local and cloud agents call the same authenticated HTTP/MCP endpoint; they do
not copy SQLite files or receive database credentials. SQLite remains available for local-only use.
This replaces the temporary EC2 installation as the prototype target.

The first deployment is one owner and one node. It establishes a shared service, not tenant
isolation or a finished commercial authentication product. Cloud coding agents should run as
separate bounded jobs and call this service; the web container is not a coding workstation.

## Deployment status: retired

On 2026-09-15 the owner explicitly authorized permanent disposal of the temporary cloud prototype,
including its cloud-only data, without creating a backup. The CloudFormation stack reached
`DELETE_COMPLETE`; the container and database are absent from the live Lightsail APIs. Its three
dedicated credentials are marked for deletion with a seven-day, no-charge recovery window. No
manual database snapshot remains. The database deletion operation reached terminal `Succeeded`;
operator notes record the exact receipts.

This supersedes the earlier offline pause. There is no remaining database to automatically restart
after seven days. Local source, local Black Box data/service, networking, and dependent local client
routes are preserved. Older separate cloud resources are outside this deployment's retirement.

The architecture proposal and future lifecycle acceptance criteria are in the
[cloud lifecycle and tenancy plan](superpowers/plans/2026-09-15-cloud-lifecycle-and-tenancy.md).
The acceptance results and pause/resume procedure below are historical/reference material. The
retired deployment cannot be resumed; a future deployment is new paid provisioning.

## Offline pause and resume

For an authorized temporary pause, first verify the AWS account, region, stack ownership, and exact
container/database names. Disable the app, wait for `DISABLED` and HTTP 503, then stop its database:

```bash
aws --profile YOUR_PROFILE --region us-east-2 sts get-caller-identity
aws --profile YOUR_PROFILE --region us-east-2 lightsail update-container-service \
  --service-name YOUR_SERVICE --is-disabled \
  --query 'containerService.{name:containerServiceName,state:state,isDisabled:isDisabled}'
```

Recheck until the container reports `DISABLED` and the verified service endpoint returns HTTP 503.
Do not proceed to the database stop if either check has not passed:

```bash
aws --profile YOUR_PROFILE --region us-east-2 lightsail get-container-services \
  --service-name YOUR_SERVICE \
  --query 'containerServices[0].{name:containerServiceName,state:state,isDisabled:isDisabled}'
curl --max-time 15 --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  'https://YOUR_VERIFIED_SERVICE_ENDPOINT/actuator/health'
```

After those checks pass, request the database stop, then recheck until it reports `stopped`:

```bash
aws --profile YOUR_PROFILE --region us-east-2 lightsail stop-relational-database \
  --relational-database-name YOUR_DATABASE \
  --query 'operations[].{id:id,status:status,errorCode:errorCode}'
aws --profile YOUR_PROFILE --region us-east-2 lightsail get-relational-database \
  --relational-database-name YOUR_DATABASE \
  --query 'relationalDatabase.{name:name,state:state,backupRetention:backupRetentionEnabled}'
```

A successful stop request is asynchronous; verify database state `stopped` before claiming the
database has stopped. The restricted queries avoid printing resolved container environment secrets.
Keep all database data, credentials, registered image identities, and restore metadata intact.

**This does not stop hosting charges.** AWS bills disabled Lightsail containers and stopped managed
databases. It automatically starts a stopped database after seven days. The app remains disabled,
but this mechanism is unsuitable for indefinite, low-cost suspension. Retirement requires a
separate reviewed operation that explicitly chooses data retention or authorized disposal: deleting
a database deletes its automatic backups. Retained snapshots, secrets, and unrelated resources may
continue to cost money.

To resume, explicitly authorize startup, start the exact database with
`lightsail start-relational-database`, wait for `available`, and re-enable the exact container with
`lightsail update-container-service --no-is-disabled`. Then verify authentication, capture/recall,
MCP, and expected persisted data. This resume sequence is documented, not exercised as part of the
September 15 shutdown.

The current CloudFormation template hardcodes `IsDisabled: false`. An operational disable creates
an intentional configuration deviation; ordinary redeployment may re-enable the app. Record the
pause and reconcile desired state before any stack update. Do not silently resume a paused service
as a side effect of deploying a new image.

Sources: [container billing](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-container-services.html),
[stopped database billing](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html),
[seven-day database restart](https://docs.aws.amazon.com/cli/latest/reference/lightsail/stop-relational-database.html),
[database backup/deletion semantics](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-databases.html).

## Permanent retirement

Retirement must separately account for CloudFormation resources marked `Retain`. Deleting this
stack removes its container, deployments, and registered images, but retains the managed database
and three generated Secrets Manager credentials. Stack deletion alone does not end their charges.

Before teardown, verify the live account/region, exact stack ARN, resource IDs, deployed template
policies, tags, exports, and external dependencies. Save a private metadata-only inventory. Identify
whether the owner wants recoverable data or explicitly authorizes disposal of the cloud-only data.
Do not create a paid backup when the owner has explicitly chosen disposal without a backup.

For an authorized permanent disposal of this prototype:

1. Delete the exact CloudFormation stack by ARN and verify `DELETE_COMPLETE`. Confirm the container
   no longer exists. Do not use `lightsail_deploy.py` for teardown: it creates/updates resources.
2. Delete the retained database by its previously verified physical name. Use
   `lightsail delete-relational-database --skip-final-snapshot` only when cloud-data disposal without
   a final backup is explicitly authorized. Verify the asynchronous operation succeeds and the
   database is absent. Its automatic backups are deleted with it.
3. Mark each of the three exact retained secret ARNs for deletion with a seven-day recovery window.
   Verify `DeletedDate` on each. They become inaccessible and stop incurring secret storage charges
   during that window; no forced immediate deletion is needed to end those charges.
4. Inventory any existing manual snapshots, custom certificates, domains, logs, or other supporting
   resources separately. Delete only individually verified, exclusively owned resources within the
   authorized scope. Domain/certificate ownership does not follow from container deletion.
5. Verify resource absence through AWS APIs and record deletion receipts. Check protected local
   services and client routes. Do not infer that unrelated resources or the entire AWS bill are zero.

The historical stack record can remain queryable by ARN after deletion; it is not a running
deployment. Keep the source/template as a rebuild recipe. A future `--apply` is new provisioning,
not a resume operation, and can create paid resources again. Do not change the template's default
retention policies merely to dispose of one explicitly authorized temporary deployment.

Sources: [container and image deletion](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-deleting-container-services.html),
[database deletion flags](https://docs.aws.amazon.com/cli/latest/reference/lightsail/delete-relational-database.html),
[secret deletion and no-charge recovery window](https://docs.aws.amazon.com/secretsmanager/latest/userguide/manage_delete-secret.html).

## Capacity, price, and known tradeoffs

The live Lightsail API in `us-east-2`, checked 2026-09-08, returns an active `micro_2_0` managed
PostgreSQL bundle at **$15/month**, with **encryption enabled**, 1 GB memory, and 40 GB storage.
This differs from the public pricing page's older $15 unencrypted entry. The deploy script reads
current bundle prices and encryption flags and refuses an unencrypted or differently priced
fallback. Query the live catalog again before creating resources:

```bash
aws --profile YOUR_PROFILE --region us-east-2 lightsail get-relational-database-bundles \
  --query 'bundles[?isActive==`true` && isEncrypted==`true` && price<=`30`].[bundleId,price,ramSizeInGb,diskSizeInGb,isEncrypted]'
```

The chosen monthly base is:

| Resource | Configuration | Monthly base |
| --- | --- | ---: |
| Lightsail container | Small, one node, 0.5 shared vCPU, 1 GB RAM | $15.00 |
| Managed PostgreSQL | Encrypted `micro_2_0`, standard single node, 1 GB RAM / 40 GB disk | $15.00 |
| Secrets Manager | Three independent generated credentials | $1.20 |
| **Total** | | **$31.20** |

The managed container includes its HTTPS endpoint, certificate, and 500 GB/month transfer quota.
There is no separate load balancer, NAT Gateway, EC2 host, or purchased domain in this stack.
Allow for API calls, transfer overages, retained snapshots/secrets, taxes, and independently running
worker jobs. A budget alert is not a hard spending cap. Retire temporary compute separately; do not leave both as permanent infrastructure.

The container runs one non-root Java 21 process with a 512 MB maximum heap, bounded metaspace/code
cache, 32 maximum HTTP threads, and four database connections. Model/embedding calls, Elasticsearch,
editor launches, and the external summary wrapper are disabled. The summary backend is explicitly
local with its non-model fallback. The next 2 GB container tier is a substantial price increase;
measure memory and latency before promising this size supports multiple active customers.

The database endpoint is publicly reachable and password authenticated, with hostname-verified TLS
required by the JDBC URL. This is supported by AWS; public reachability is not anonymous data
access. Lightsail does not expose a normal database security-group source allowlist. The prototype
uses generated 48-character credentials and verifies that plaintext PostgreSQL startup is rejected
before deploying any app image. Do not describe this as verified private container-to-database
networking. The app owns database access; ordinary clients only use HTTPS and API credentials.

App Runner was not chosen for this slice because its documented **120-second total HTTP request
limit** conflicts with indefinite SSE connections, and private-VPC outbound access requires an
additional egress design for public services such as Linear. Fargate/ECS remains a valid growth path,
but a separate public load balancer and its IP charges add overhead at this budget. A shared ECS
cluster is not the same as one host per customer; it can be evaluated when a concrete capacity or
networking requirement justifies the added operation.

## Files and responsibility

- `Dockerfile.cloud` supplies the cloud image; the existing `Dockerfile` and local volume behavior remain available.
- `infra/aws/lightsail.json` owns the database, container service, and generated secrets.
- `scripts/cloud/lightsail_deploy.py` validates the account, region, current prices/encryption,
  resource ownership, and CloudFormation change set before deployment. It registers immutable
  private image versions but changes service configuration only through CloudFormation.
- `scripts/cloud/lightsail_build.py` builds a reviewed JAR with a pinned Java base-image digest,
  pinned AWS regional CA checksum, source revision label, and verified JAR checksum.
- `scripts/cloud/lightsail_test.py` checks the deployment safety boundaries with fixtures.

The stack has three separate generated Secrets Manager values: database password, browser password,
and API bearer token. They are passed through CloudFormation dynamic references. No secret value
belongs in command-line arguments, template parameters, shell history, image layers, or deployment
logs. CloudFormation does not persist the resolved values in its logs, but Lightsail container
configuration readers may see resolved environment values. Limit those IAM permissions. Rotating
a secret also requires updating/redeploying its consumer; a dynamic reference alone is not a
runtime secret-refresh mechanism.

## Prepare resources without deploying an app

Prerequisites: AWS CLI v2, Python 3.9+, Docker with Linux/amd64 build support, and the official
Lightsail Control plugin for private image uploads. On macOS, AWS documents installation with
`brew install aws/tap/lightsailctl`; install it before the image-push phase. A resource-only
preparation does not require Docker or lightsailctl.

Run from the checkout that contains these files:

```bash
python3 scripts/cloud/lightsail_test.py
python3 scripts/cloud/lightsail_deploy.py \
  --profile YOUR_PROFILE --region us-east-2 --account-id YOUR_12_DIGIT_ACCOUNT \
  --stack-name blackbox-cloud --service-name blackbox-cloud --prepare-only
```

That is a read-only preflight. Review its account, region, selected encrypted bundle, PostgreSQL
blueprint, and price. Add `--apply` to the same command to create the resources. The initial
container has no app deployment or public application handler. PostgreSQL provisioning can take
several minutes; the script waits for availability.

All infrastructure changes use a CloudFormation change set. Existing-resource removal and definite
or conditional replacement are rejected before execution. An empty update is handled without
executing a change set. Repeating resource preparation preserves any already deployed image and
pins the existing database blueprint instead of silently upgrading its engine.

The new database sets `rds.force_ssl=1`. The script verifies both a certificate-validated TLS
handshake and rejection of a plaintext PostgreSQL StartupMessage. The latter contains only the
known username/database, never a password. If that parameter is pending on a newly created DB,
the script may reboot only that exact new database once, then requires the probe to pass. An
existing database that accepts plaintext causes a failure requiring inspection; it is not
restarted automatically.

## Build and deploy the integrated app

Use the checkout containing the **integrated, tested PostgreSQL and authentication changes**.
Do not build from the earlier EC2-only snapshot. Run the appropriate repository tests and real
PostgreSQL/authentication checks, then package the JAR. The build helper rejects a JAR missing the
PostgreSQL profile, JDBC driver, or authentication configuration classes.

If a local service runs the JAR in this checkout, build in an isolated clone instead. Never
replace a packaged JAR underneath a running JVM. The local service's own deployment script
remains responsible for its restart; deploying this cloud image does not update that service.

```bash
mvn -q -DskipTests package
python3 scripts/cloud/lightsail_build.py \
  --jar target/sba-agentic-0.1.0.jar --source-revision REVIEWED_REVISION
python3 scripts/cloud/lightsail_build.py \
  --jar target/sba-agentic-0.1.0.jar --source-revision REVIEWED_REVISION --apply
```

`--apply` here only builds a local Docker image. Record the emitted image tag, exact image ID,
source revision, and JAR SHA256. These metadata checks establish artifact identity; they do not
replace behavior tests. The Docker build itself verifies that the copied JAR matches its checksum.

The upload command is a custom AWS CLI plugin operation. `lightsailctl` emits a plain-text
registration receipt even when `--output json` is supplied; the deploy helper parses that exact
receipt from either output stream and requires the requested service and label. It never selects
an image by guessing the newest version. See the [official plugin implementation](https://github.com/aws/lightsailctl/blob/v1.0.8/internal/cs/pushimage.go).

If upload succeeds but receipt parsing or a later deployment step fails, inspect
`aws lightsail get-container-images --service-name blackbox-cloud` before taking another write
step. Do not blindly repeat the upload. Compare the exact registered image and registry manifest
with the reviewed local image, then use the existing `--rollback-image` option to deploy that
verified registered version through CloudFormation without uploading again. Docker may report an
OCI image-index digest while Lightsail reports its platform manifest digest. Match the registered
digest to the platform manifest retained from the verified build, or inspect the corresponding
index/manifest/config chain; do not assume all image identifiers represent the same object.

After local container verification under `--memory=1g --cpus=0.5`, use those exact values:

```bash
python3 scripts/cloud/lightsail_deploy.py \
  --profile YOUR_PROFILE --region us-east-2 --account-id YOUR_12_DIGIT_ACCOUNT \
  --stack-name blackbox-cloud --service-name blackbox-cloud \
  --image IMAGE_TAG --image-id sha256:EXACT_IMAGE_ID --source-revision REVIEWED_REVISION
```

Review the preflight, then repeat with `--apply`. The script pushes that image to Lightsail's
private image store and uses the returned **numbered image version**, never `latest`. It waits for
that exact version to become active. Credentials are fetched only in memory for the authenticated
status check; the verifier rejects redirects so an Authorization header cannot follow one to
another destination. The deployment prints the HTTPS URL and a compact verification result.

The cloud environment contract is:

```text
SPRING_PROFILES_ACTIVE=postgres
SBA_DATASOURCE_URL=jdbc:postgresql://HOST:5432/blackbox?sslmode=verify-full&sslrootcert=/opt/blackbox/certs/us-east-2-bundle.pem
SBA_DATASOURCE_USERNAME=blackbox
SBA_DATASOURCE_PASSWORD=<generated database credential>
SBA_DATASOURCE_POOL_SIZE=4
SBA_AUTH_ENABLED=true
SBA_AUTH_USERNAME=blackbox
SBA_AUTH_PASSWORD=<separate generated browser credential>
SBA_AUTH_API_TOKEN=<separate generated API bearer token>
SBA_AUTH_SECURE_COOKIES=true
```

Lightsail terminates HTTPS and forwards HTTP to container port 8766. Authentication redirects are
relative and cookies are explicitly secure; the deployment does not enable blanket trust in
forwarded headers. Only `GET /actuator/health` is the minimal anonymous health endpoint.

## Acceptance and ongoing use

Before calling the cloud service ready:

1. Verify the certificate-valid HTTPS URL, successful minimal health response, and rejection of
   unauthenticated `/api/status`, `/api/recall`, and `/mcp` requests.
2. Verify browser login, secure session cookie, logout, and an authorized API request. Retrieve
   credentials through the approved secret-access flow without pasting them into task output.
3. Capture a unique decision through the authenticated public API, recall it from a second client,
   restart/redeploy the container, and recall that same event again.
4. Exercise a real MCP initialize/tool call and a sustained SSE stream. Lightsail's public docs do
   not state a reliable container request/idle timeout; the health-check timeout is a different
   setting. Measure connection survival/reconnect and event delivery rather than assuming support.
5. Check memory, latency, and database connection use at the chosen 1 GB limits. Keep the node count
   at one until process-local sessions/SSE behavior has an intentional multi-node design.

The initial live Lightsail acceptance observed an idle SSE connection close after 60.30 seconds;
the browser reconnected and subsequent event delivery and logout revocation passed. Black Box now
sends an SSE comment heartbeat every 15 seconds using one shared Spring scheduler. Comments do not
create records or trigger frontend event handlers. Every heartbeat rechecks browser session validity,
so logout/expiry also closes an otherwise idle stream without waiting for a new agent write.
Completion, timeout, network error, and application shutdown remove subscribers.

The final cloud acceptance kept the original stream open for 75 seconds with one browser open
and zero errors. Event delivery, bearer reconnection, browser logout revocation, real MCP recall,
and readback of the original capture after two container replacements passed. Post-completion
logs contained no authentication-cleanup errors. This is an idle-timeout mitigation, not a promise
of indefinite connection survival. Repeat the measured cloud observation after future deployments
and retain EventSource reconnection; the API/database remain authoritative. Long-running agent work must be an asynchronous job that records progress
and handoffs, not a single long HTTP request.

## Recovery and rollback

The database has automated backup retention enabled, and CloudFormation retains it and all three
secrets on stack deletion/replacement. Retained resources continue to cost money. Keep a tested
logical PostgreSQL backup/restore process as well; container filesystem data is disposable.
Never use `/data` SQLite storage as cloud persistence on Lightsail Containers.

To roll back the app, use an exact previously registered image version:

```bash
python3 scripts/cloud/lightsail_deploy.py \
  --profile YOUR_PROFILE --region us-east-2 --account-id YOUR_12_DIGIT_ACCOUNT \
  --stack-name blackbox-cloud --service-name blackbox-cloud \
  --rollback-image :blackbox-cloud.IMAGE_LABEL.VERSION
```

The default still performs a dry run. Confirm database compatibility before adding `--apply`.
The rollback is another guarded CloudFormation update, not an out-of-band container deployment.
If a deployment fails, inspect the selected stack and container state; do not create a duplicate
stack or print container environment dumps. A CloudFormation database bundle/engine change may
require an explicit migration instead of an automatic resource replacement.

Sources: [Lightsail pricing](https://aws.amazon.com/lightsail/pricing/),
[live database bundle API](https://docs.aws.amazon.com/lightsail/2016-11-28/api-reference/API_GetRelationalDatabaseBundles.html),
[Secrets Manager pricing](https://aws.amazon.com/secrets-manager/pricing/),
[container HTTPS endpoints](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-container-services.html),
[PostgreSQL certificate verification](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-connecting-to-postgres-database-using-ssl.html),
[regional CA bundles](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-download-ssl-certificate-for-managed-database.html),
[CloudFormation secrets](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/dynamic-references-secretsmanager.html),
[App Runner request limit](https://docs.aws.amazon.com/apprunner/latest/dg/develop.html).
