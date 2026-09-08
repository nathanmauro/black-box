# Optional shared-prototype authentication

Authentication is disabled by default for the existing loopback-only local application. A shared
cloud deployment must enable authentication before exposing its listener. This is one trusted
prototype workspace with one user and one agent token. It does not provide tenant isolation,
per-agent permissions, invitations, or an OAuth authorization server.

## Configuration

| Environment variable | Meaning |
| --- | --- |
| `SBA_AUTH_ENABLED` | Set `true` for every network-accessible deployment. Default `false`. |
| `SBA_AUTH_USERNAME` | Browser login name. Default `blackbox`. |
| `SBA_AUTH_PASSWORD` | Independently generated browser password, at least 32 characters. |
| `SBA_AUTH_API_TOKEN` | Separate independently generated agent credential, at least 32 characters. |
| `SBA_AUTH_SECURE_COOKIES` | Default `true`. Keep enabled behind the managed HTTPS endpoint. Set `false` only in a trusted loopback HTTP fixture. |

Inject credentials from a secret manager or protected runtime environment. Generate both secrets
independently with a cryptographically secure generator; length validation is not an entropy
guarantee. Startup fails with a configuration-variable name (never its value) for missing, short,
whitespace-containing, obviously repetitive, or identical credentials. Rotate by replacing the
runtime secrets and restarting; in-memory browser sessions end on restart. Do not put secrets in
command arguments, checked-in files, URLs, logs, or agent memory.

The browser session cookie is `HttpOnly`, `SameSite=Lax`, `Secure` by default, and expires after
30 minutes of inactivity. Session identifiers are cookie-only; URL rewriting is disabled. The
application emits fixed relative authentication redirects and does not enable forwarded-header
trust. A managed HTTPS proxy such as Lightsail can terminate TLS and forward HTTP internally while
`SBA_AUTH_SECURE_COOKIES=true` protects the external browser cookie. Keep the internal listener
unreachable directly from the public network. Proxy access control and TLS remain deployment duties.

## Browser use

Open the app and sign in at `/login`. Spring Security owns password verification, session fixation
protection, the generated login form, CSRF checks, and logout. The SPA reads the `XSRF-TOKEN` cookie and
sends `X-XSRF-TOKEN` on every mutation through its API wrapper. The session identifier is unreadable
to JavaScript. A page load after login/logout refreshes the CSRF cookie. Existing GET/SSE streams use
the browser session and need no CSRF header. Streams recheck session validity and inactivity before
every publication, so logout or expiry prevents later event delivery. An idle invalidated stream
closes on its next attempted publication. An expired session produces JSON `401` from API calls;
reload the page to sign in again.

Visit `/logout` for the standard logout confirmation, then submit it. Logout is a CSRF-protected POST
and invalidates the browser session. Login/logout redirects stay relative even when untrusted
forwarded headers are supplied.

## Agent and MCP use

Send `Authorization: Bearer <agent-secret>` on **every** HTTP API or streamable MCP request to `/mcp`.
Configure the client to read its secret from a protected environment or credential store. The token
has full-workspace privileges, like the browser user.

Any request with an `Authorization` header enters a separate stateless Spring Security resource
server chain. Only a valid Bearer credential authenticates it. Malformed, wrong-scheme, or invalid
headers cannot fall back to a logged-in browser session. This chain exempts CSRF because authorization
requires the explicit agent credential before a controller can run. It creates no session or CSRF
cookies. Removing the header from a subsequent agent request returns `401`.

App pages, static assets, APIs, streams, MCP, and actuator information/metrics are protected. Only
the login/logout flow (including Spring's login-page CSS) and exact GET `/actuator/health`,
`/actuator/health/liveness`, and `/actuator/health/readiness` are public. Health exposes status and
configured group names, without component or diagnostic details. Keep
`management.endpoint.health.show-details` and `management.endpoint.health.show-components` at their
default `never` values.

## Verification and sources

`mvn -Dtest=AuthSettingsTest,AuthenticationHttpTest,SecureAuthenticationCookiesHttpTest,SpaForwardingTest test` starts an isolated real HTTP
server and temporary database, checks anonymous denial, logs in using the real form, captures with
session + CSRF, rejects missing/wrong CSRF and malformed Bearer with a valid browser session, captures
with a stateless agent, initializes MCP, connects SSE, logs out, and checks that only authorized
records were saved. Generated credentials exist only in the test process. The loopback fixture
explicitly disables secure cookies; production defaults remain secure.

The implementation follows [Spring Security form login](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/form.html),
[the documented SPA CSRF handler](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html),
and [opaque Bearer authentication](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/opaque-token.html).

After packaging, `node frontend/tests/auth-browser-smoke.mjs` runs headless Chromium against its own
temporary database/server and uses the actual login, New story form, and logout pages. It verifies
that the generated frontend sends CSRF on spec/task creation. It never reuses the live local service.
