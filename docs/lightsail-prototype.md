# Shared managed-container prototype

Black Box runs as one shared HTTPS application in Lightsail Containers, with canonical data in
managed PostgreSQL. Local and cloud agents call the same authenticated HTTP/MCP endpoint; they do
not copy SQLite files or receive database credentials. SQLite remains available for local-only use.
This replaces the temporary EC2 installation as the prototype target.

The first deployment is one owner and one node. It establishes a shared service, not tenant
isolation or a finished commercial authentication product. Cloud coding agents should run as
separate bounded jobs and call this service; the web container is not a coding workstation.

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

There is no promise that a permanently idle SSE socket survives indefinitely. The current UI uses
EventSource reconnection, and its authoritative data remains in the API/database; test reconnect
behavior and consider heartbeats if the managed endpoint closes idle streams. Long-running agent
work must be an asynchronous job that records progress and handoffs, not a single long HTTP request.

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
