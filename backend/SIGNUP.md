# Standard account signup

This ticket implements only `POST /api/auth/signup`. The project owner approved email/password
signup with **Confirm email disabled**, JSON session delivery after application persistence, and
operator-assisted reconciliation after persistent partial failures. This supersedes the earlier
verification/completion-endpoint proposal and the broader authentication UI plan for this ticket.
Login was subsequently implemented in a separate ticket; see [login](LOGIN.md). No profile,
refresh, confirmation callback, completion endpoint, or frontend integration is implemented.
No schema migration is needed; preserve the already-applied V001.

## Contract

With `SIP_AUTH_ENABLED=true` and a database-capable profile, send `Content-Type: application/json`:

```json
{
  "email": "student@example.com",
  "password": "SecurePassword123!",
  "firstName": "Jane",
  "lastName": "Doe"
}
```

Only these four string fields are accepted. Missing/null/non-string/unknown fields are rejected,
including `role`, `id`, and `organizationId`. Email must be nonblank, formatted, at most 255 Java
characters, and have no surrounding whitespace. Names must be nonblank and fit 100 Unicode code
points. Names are preserved exactly; no trimming occurs. Password must be nonblank and at most
1024 Java characters; the configured Supabase policy decides minimum strength, required characters,
and leaked-password restrictions. Passwords are never trimmed. NUL/unpaired surrogate input is
rejected. The entire request is capped at 16 KiB, including chunked bodies.

Success is `201 Created`, `Cache-Control: no-store`, with exactly this allowlisted structure:

```json
{
  "user": {
    "id": "b6034174-d377-4ad4-b96a-6777660143e6",
    "email": "student@example.com",
    "role": "STANDARD",
    "firstName": "Jane",
    "lastName": "Doe"
  },
  "session": {
    "accessToken": "<supabase-access-token>",
    "refreshToken": "<supabase-refresh-token>",
    "tokenType": "bearer",
    "expiresIn": 3600,
    "expiresAt": 1790000000
  }
}
```

Tokens and times above are illustrative; actual values come from Auth. `expiresAt` is Unix seconds.
The session is released only after `UserService` returns from its committed transaction. Never
cache or log this response. The backend does not save sessions or passwords. Browser integration
will need to adopt the returned session through the supported Supabase client and define safe
storage/XSS controls separately. JSON delivery exposes tokens to JavaScript; HttpOnly cookies would
require a different CSRF/cookie/refresh/session design and are not implemented.

Errors use `{"code":"STABLE_CODE","message":"Safe message."}`, with no rejected field values,
tokens, raw provider messages, or exception chains. Signup responses, including errors, use
`Cache-Control: no-store`.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Invalid fields/JSON or recognized provider input/password rejection |
| 409 | SIGNUP_CONFLICT | Registration cannot proceed; no account details disclosed |
| 413 | REQUEST_TOO_LARGE | Body exceeds 16 KiB |
| 415 | UNSUPPORTED_MEDIA_TYPE | JSON required |
| 429 | AUTH_RATE_LIMITED | Provider rate limit; try later |
| 502 | AUTH_RESPONSE_INVALID | Missing/ambiguous session, mismatched identity, unexpected Auth rejection/configuration or malformed response |
| 503 | AUTH_UNAVAILABLE | Timeout, connection failure, or provider 5xx; Auth creation outcome may be unknown |
| 503 | ACCOUNT_PROVISIONING_FAILED | Auth succeeded but application provisioning/session delivery could not finish; contact an operator |
| 500 | INTERNAL_ERROR | Sanitized unexpected failure |

Recognized duplicate provider codes are `user_already_exists` and `email_exists` on 400/422.
Signup accepts symbolic `code` responses and unversioned numeric `code` plus symbolic
`error_code` responses. A present `error_code` takes precedence, including when malformed;
unknown codes remain sanitized upstream failures. This prevents duplicate-email and
recognized input rejections from incorrectly becoming HTTP 502.
A generic 409 still discloses that registration cannot proceed; this is the approved enumeration
tradeoff. There is no public email lookup or duplicate-to-login fallback.

## Trust and consistency

1. Call the standard `/auth/v1/signup` once with `apikey: <publishable-key>`. Name metadata is
   allowlisted as `first_name`/`last_name` to aid authorized recovery, never for authorization.
2. Require a valid UUID/email and complete, unexpired bearer session. A user-only, fake/obfuscated,
   or confirmation-required response causes no application database writes.
3. Validate the returned access token with authenticated `/auth/v1/user`. Require the same UUID
   and exact email, and reject anonymous identities. No local JWT decoding is trusted.
4. Persist the exact Auth email through `UserService.createStandardAccount`. The application does
   not lowercase emails, introduce case-insensitive lookups, generate UUIDs, or accept role inputs.
   Existing case-sensitive uniqueness semantics remain intact. Provider normalization of the
   submitted email is accepted only when the two provider responses agree.
5. Return the committed application account plus the allowlisted session. Never expose Auth
   metadata, identities, privileged credentials, or an unrelated application's account.

Automatic email confirmation **does not prove mailbox ownership**. A successful account must not
be used as evidence of university affiliation, organization authority, or verified email ownership.
Supabase JWT claims/confirmation timestamps under this configuration do not change that limitation.
Future protected routes must authenticate/authorize independently and fail closed for missing
application accounts. This signup implementation is not a general security filter for other routes.

Auth calls use HTTPS in enabled application configuration, never follow redirects, and accept at
most 64 KiB per upstream response. Timeouts cover the entire body. There are no application-level
Auth retries. Raw provider/driver exceptions are discarded. Sensitive DTO string representations
are redacted, Hibernate diagnostic suppression is retained, and MVC exception diagnostics/JDK HTTP
wire diagnostics are suppressed. Do not enable request/body/header tracing, APM payload capture,
or external proxy logs that include credentials. Ordinary access logs should contain status/path
only, never request/response bodies or authorization headers.

## Partial failures and recovery

Auth and JDBC are separate systems, without a distributed transaction. After a transient JDBC,
connection, transaction-start, or uncertain commit failure, the service allows **one** retry using
the already validated Auth identity. Each `UserService` invocation has its own transaction; there
is no enclosing signup transaction. Before retrying, read by the trusted UUID to detect a commit
whose acknowledgement was lost. A duplicate insert race is recoverable only if the UUID, exact
email, and STANDARD role match. Preserve stored names/profile/role; never overwrite during retry.
An email owned by another UUID or unexpected role/email is a conflict, not an account merge.
Non-transient integrity failures are not retried.

After persistent failure, retain the Auth identity, return no session, and require operator help.
A second HTTP signup request can be rejected as a duplicate and **cannot** authorize reconciliation.
Never recreate or delete identities automatically. A timeout during signup/user lookup can also
leave an Auth-only identity. A response lost after commit can leave both records created but the
client without its session. The separate [login endpoint](LOGIN.md) can restore session access
when the application account exists; signup has no automatic login fallback.

### Operator-assisted reconciliation runbook

This is a manual maintenance procedure, not a public endpoint or scheduled job. Every live use
requires separate authorization for the target environment and record. Do not run it as a smoke
test, during automated tests, or merely because someone claims an email address.

1. In the authorized Supabase project's Auth dashboard, locate the original signup using trusted
   operator evidence/audit information. Confirm its canonical UUID and current Auth email directly
   from Auth, not a support request's UUID/email alone. Review submitted name metadata as profile
   data only. Do not retrieve/copy passwords or sessions. Resolve ambiguous ownership/support
   requests through the team's support process; email auto-confirmation is not proof of ownership.
2. Review the application row by that UUID using the existing service. If the exact UUID/email and
   STANDARD role already match, leave it alone. If another UUID owns that email, the role differs,
   or Auth email and application email differ, stop for explicit review. Never link by email,
   rename the Auth identity, alter privileges, or overwrite an account to force a match.
3. If the application row is absent and reconciliation is authorized, call
   `UserService.createStandardAccount(trustedUuid, trustedEmail, firstName, lastName)` in an isolated
   maintenance application context. It fixes STANDARD, preserves existing checks and handles races.
   Re-read after a duplicate in a new transaction and apply the same identity checks.
4. Record the operator/action/result in a private team record without credentials or tokens.
   Reconciliation creates no new session. Session access requires the separately implemented
   client/Auth sign-in flow; repeatedly calling signup is not recovery.

For an authorized maintainer, one way to invoke the service without adding any HTTP endpoint is
JShell against the built classes/dependencies (JDK 21+). Use a private local console, never a shared
notebook or recorded terminal; its command history will contain the supplied profile information.
Export the authorized
database profile/credentials privately; Spring does not load `.env`. From `backend/`:

```sh
./mvnw -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/recovery-classpath.txt
jshell --class-path "target/classes:$(cat target/recovery-classpath.txt)"
```

In JShell, start a non-web context with signup disabled and obtain the proxied service:

```java
import org.springframework.boot.SpringApplication;
import edu.gatech.sip6321.SipApplication;
import edu.gatech.sip6321.account.UserService;
var context = SpringApplication.run(SipApplication.class, "--spring.main.web-application-type=none", "--SIP_AUTH_ENABLED=false");
var users = context.getBean(UserService.class);
```

Apply steps 1–3 with reviewed values and service methods; do not print complete account objects or
exception chains. Close the context with `context.close()` and exit JShell. This procedure is not
executed by the signup service. There is no durable queue or automatic reconciliation across
process crashes; persistent failures require this operator workflow.

## Safe database diagnostics

The diagnostic-only patch emits `db.diagnostic` key/value log lines at INFO for start/success and
WARN for failures. No new environment variables, log-level changes, connection attempts, SQL,
timeout changes, pool tuning, or TLS changes are introduced. Deployment remains a separately
authorized action. The API response, original exception objects, retry count, and transaction
boundaries are preserved.

`account.create` and `account.lookup` enclose calls to the transactional UserService proxy, including
transaction initialization and completion. Every operation is timed with a monotonic clock.
`connection.acquire` encloses the existing Hikari `getConnection()` call in production profiles,
including lazy pool initialization; the returned connection is passed through unchanged.

Each signup gets a new server-generated `correlationId`, unrelated to any account UUID or request
header. Account operations and their synchronous connection acquisitions share it across both
attempts. Scope state is removed/restored on success and failure, including reused request threads.
An acquisition outside signup (for example, the existing startup connectivity probe) gets its own
correlation ID, with `parentOperation=none` and `attempt=0`. The correlation ID is log-only; no new
response field/header is added.

Allowlisted fields are `correlationId`, `operation`, `parentOperation`, `attempt`, `event`,
`connectionAcquired`, `elapsedMs`, `exception`, `sqlException`, and `sqlState`. Exception values
are class names only. SQLSTATE extraction follows nested causes, chained SQLExceptions, and the
original exception retained by TransactionSystemException, with cycle/size bounds. Only five-character
uppercase alphanumeric SQLSTATE values are emitted; missing/invalid codes become `unknown`.
No exception messages, stack traces, SQL parameters, URLs, credential values, request bodies,
tokens, names, email addresses, or account identifiers are passed to the diagnostic logger.

Example failure lines (illustrative values, not a diagnosis of the live deployment):

```text
db.diagnostic correlationId=57b54be4-a990-40c2-9340-6ddd021fd21a operation=connection.acquire parentOperation=account.create attempt=1 event=failure connectionAcquired=false elapsedMs=10005 exception=PSQLException sqlException=PSQLException sqlState=08001
db.diagnostic correlationId=57b54be4-a990-40c2-9340-6ddd021fd21a operation=account.create parentOperation=none attempt=1 event=failure connectionAcquired=false elapsedMs=10010 exception=CannotCreateTransactionException sqlException=PSQLException sqlState=08001
```

After an approved deployment, use Render logs as follows:

1. Search for `db.diagnostic` and `event=failure` near the observed request time, then filter by
   that line's `correlationId`. It identifies a single signup without disclosing its account.
2. Inspect the **first** failure as well as subsequent failures. Attempt 1 is initial account
   creation; attempt 2 first checks for an existing committed account, then creates only if absent.
   A duplicate race can also cause `account.lookup` during attempt 1.
3. `connection.acquire event=failure connectionAcquired=false` means Hikari did not return a usable
   connection to the caller. Investigate connection establishment/pool acquisition before changing
   account SQL or grants. It does not prove that no physical TCP/DB connection was ever established.
4. An account operation failing with `connectionAcquired=true` reached a handed-out connection;
   investigate subsequent transaction setup, queries, flushing, or commit using the SQLSTATE.
5. `connectionAcquired=unknown` means this operation did not observe acquisition through the
   instrumented datasource. Do not interpret it as `false`. The development `database` profile
   retains its original Boot datasource and can report `unknown`; production uses the instrumented
   Hikari subclass. State resets between operations.
6. A `connection.acquire event=start` with no terminal event identifies an acquisition still in
   progress at that point (or an interrupted process/log stream). A long elapsed time alone is not
   proof of a particular timeout; correlate it with exception class and SQLSTATE.

Common codes from the [PostgreSQL SQLSTATE reference](https://www.postgresql.org/docs/current/errcodes-appendix.html):

| SQLSTATE | Interpretation |
| --- | --- |
| 08001 | Client unable to establish SQL connection |
| 08006 | Connection failure |
| 28P01 | Invalid password |
| 42501 | Insufficient privilege |
| 23503 | Foreign-key violation |
| 40001 | Serialization failure |

A code is evidence of the reported failure category, not necessarily its complete cause. For
example, 08001 does not distinguish every DNS, TCP, TLS, or pooler failure. Driver-only failures
can have no SQLSTATE. Known duplicates sanitized by UserService may also have `sqlState=unknown`.
The existing startup connectivity probe still suppresses Hikari/driver logger namespaces, but it
does not suppress this application's `edu.gatech.sip6321.diagnostics.DatabaseDiagnostics` logger.
Do not enable general JDBC/Hikari DEBUG logging to recover sensitive exception messages.
Auth-only failures before any account operation do not emit these database diagnostics.

## Supabase settings before live testing

The project owner/operator must review these in the **same project** used by Render's JDBC URL:

1. Authentication configuration: allow new users to sign up; enable the Email provider and
   email/password signup; turn **Confirm email OFF**, as approved. Do not change phone/provider
   settings just to influence duplicate responses; ambiguous responses fail closed.
2. Set the desired password policy in Supabase. The backend intentionally adds no minimum-length
   or character-class policy. Provider password/input rejection is mapped safely to 400.
3. Under project API keys, copy the modern `sb_publishable_...` key and project HTTPS URL.
   Legacy anon JWTs, `sb_secret_...`, and service-role keys are not accepted by this implementation.
4. Inspect CAPTCHA/bot protection. The approved request has no CAPTCHA token field, so CAPTCHA-
   required signup is incompatible and fails safely. If enabled, obtain approval to extend the
   contract rather than bypassing it or automatically disabling protection.
5. Review Auth rate limits and hooks. Requests originate from Render; clients can share upstream
   IP limits. No client-supplied forwarding headers are trusted/sent to bypass them. This ticket
   adds no application-wide rate-limiting infrastructure.
6. Confirm V001 is already applied, the Auth UUID FK remains present, and the Render JDBC role can
   SELECT/INSERT the table under its existing RLS strategy. Keep browser access revoked. Do not
   rerun/edit V001 or add public write policies. An ordinary restricted role needs reviewed grants
   and RLS access; grants alone are insufficient.

No confirmation SMTP, email redirect/callback URL, or completion endpoint is needed for this flow.
Existing site/redirect settings can stay as they are. No Supabase settings are changed by the code.

## Render settings before live testing

Use the existing backend service. Configure these in Render's environment UI; never put credentials
in source control or `VITE_*`:

| Setting | Value |
| --- | --- |
| SPRING_PROFILES_ACTIVE | `prod` (Docker's existing default); `render` remains its compatibility alias |
| SIP_AUTH_ENABLED | `true` |
| SUPABASE_URL | `https://<your-project-reference>.supabase.co` (origin only, optional trailing slash) |
| SUPABASE_PUBLISHABLE_KEY | Modern `sb_publishable_...` key for that same project |
| SIP_AUTH_CONNECT_TIMEOUT_MS | Optional, default `3000`; range `1..30000` |
| SIP_AUTH_REQUEST_TIMEOUT_MS | Optional, default `10000` per Auth request, including body; range `1..30000` |
| SPRING_DATASOURCE_URL | Preserve the existing JDBC URL with `sslmode=require`, or existing reviewed `verify-full` setup |
| SPRING_DATASOURCE_USERNAME | Preserve the existing authorized JDBC role |
| SPRING_DATASOURCE_PASSWORD | Preserve the existing database password |
| SIP_CORS_ALLOWED_ORIGINS | For browser calls, exact frontend origin(s), comma-separated; no paths/wildcards/trailing slash |

Render supplies `PORT`. Do not combine `prod`/`render` with `local` or `database`. Missing/invalid
Auth configuration fails startup when enabled, without printing the key. Default local mode keeps
Auth disabled and database-free; the signup route is absent until explicitly enabled. Merely
enabling Auth with `local` is invalid because `UserService` requires a database-capable profile.
Do not set a secret/service-role key, signing secret, or frontend variable for this backend flow.

The local changes must be reviewed and deployed through a separately authorized deployment before
the existing Render URL can serve this endpoint. Health remains `GET /api/health`; it proves neither
Auth readiness nor account-table privileges. Live endpoint testing creates real accounts, so use
an explicitly authorized development project/test account first. Response/session values must not
be pasted into shared logs, screenshots, or tickets.

## Verification

The diagnostic-only patch was verified locally on November 1, 2026:

| Command | Ordinary tests | PostgreSQL tests | Failures / errors / skips |
| --- | --- | --- | --- |
| `./mvnw verify` | 159 passed | Not requested | 0 / 0 / 0 |
| `./mvnw -Ppostgres-it verify` | 159 passed | 16 passed | 0 / 0 / 0 |

Both final runs completed with BUILD SUCCESS, Spotless checks, and JAR packaging. The first
PostgreSQL attempt could not start because Docker Desktop was stopped; after starting Docker
locally, the full rerun passed. The patch adds 14 ordinary diagnostic tests and one real-connection
PostgreSQL test, and strengthens existing deployment assertions for unchanged pool/timeouts.
No production users, records, settings, commits, or deployments were created or changed.
Whitespace checks and local documentation links were also reviewed.

Original signup implementation verification on November 1, 2026 (before the diagnostic patch):

| Command | Ordinary tests | PostgreSQL tests | Failures / errors / skips |
| --- | --- | --- | --- |
| `./mvnw verify` | 145 passed | Not requested | 0 / 0 / 0 |
| `./mvnw -Ppostgres-it verify` | 145 passed | 15 passed | 0 / 0 / 0 |

Both commands completed with BUILD SUCCESS, Spotless checks, and executable JAR packaging.
The local runtime was OpenJDK 22.0.1 compiling for Java 21, with Docker Desktop and isolated
`postgres:17-alpine`. The 160 distinct tests include 78 new ordinary signup tests and five new
signup persistence/recovery integration tests. No live Supabase access was used. Frontend checks
were not rerun because frontend source/configuration is unchanged. Tracked/staged whitespace checks,
new Java-file whitespace review, signup documentation links, and changed-file secret review passed.

Run `./mvnw verify` and `./mvnw -Ppostgres-it verify`. Ordinary tests use local HTTP stubs and mocked
Auth, never Supabase. The opt-in suite runs the real V001 against an isolated PostgreSQL container
with an Auth fixture. Signup tests cover validation/unknown fields, duplicates, provider rejection,
fake/missing sessions, UUID/email mismatch, safe responses/logs, upstream byte/time bounds,
STANDARD assignment, real transaction rollback/retry, uncertain commits, persistent failure and
manual service recovery. Frontend code is unchanged.

The referenced decision register `docs/development-decisions.md` is still absent in this checkout.
This approved ticket resolves its D07 references only for signup; broader D02 role and D09 access
questions remain outside scope. See [account persistence](ACCOUNTS.md), [API](../docs/api.md), and
[security](../docs/security.md).

Provider references: [Auth REST specification](https://github.com/supabase/auth/blob/master/openapi.yaml),
[authenticated user lookup](https://supabase.com/docs/reference/javascript/auth-getuser), and
[password policy](https://supabase.com/docs/guides/auth/password-security).

### Signup error-format regression fix — November 5, 2026

A read-only Auth settings check confirmed signup is enabled and email auto-confirm is true.
A deliberately incomplete Auth signup request (no email/password, no identity created)
confirmed the project returns numeric `code` plus symbolic `error_code`. Local stub tests
reproduced recognized signup errors becoming HTTP 502 before the fix. Signup now uses
the same precedence rule as login. Ten new cases cover duplicate/input rejection and
malformed/conflicting symbolic fields; no provider message or token is exposed.

`./mvnw spotless:apply verify` with Java 21 passed 261 ordinary tests, formatting, and
JAR packaging. No full live account signup was performed for this fix, and no schema,
Auth setting, account, deployment, commit, or push was changed. The optional PostgreSQL
integration suite was not requested. This fixes error classification; successful live
signup/session delivery still requires verification with an authorized test account.
