# Account login

`POST /api/auth/login` is enabled alongside [signup](SIGNUP.md) by `SIP_AUTH_ENABLED=true`
with a database-capable profile. No new migration, dependency, Render variable, CORS policy,
or Spring Security configuration is required. Profile, refresh/logout endpoints, MFA challenge
handling, and frontend session integration remain separate work.

## Contract

Send `Content-Type: application/json` with exactly two string fields:

```json
{"email":"student@example.com","password":"UserPassword123!"}
```

Email must be nonblank, formatted, at most 255 Java characters, with no surrounding whitespace.
Password must be nonblank and at most 1024 Java characters. Values are preserved; passwords are
never trimmed or checked against a new strength policy. NUL/unpaired surrogates, unknown fields,
nulls, and scalar coercion are rejected. The entire request is capped at 16 KiB.

Success is HTTP 200 with `Cache-Control: no-store`, using the same allowlisted DTO as signup:

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

Times/tokens are illustrative; `expiresAt` is Unix seconds. Account fields come from `public.users`;
login does not change stored names, email, role, or profile. The UUID is the identity key even if
application email has drifted. Login does not reconcile email drift or grant additional permissions.
All existing account roles can authenticate; authorization of protected routes remains separate.

Errors have the existing `{"code":"STABLE_CODE","message":"Safe message."}` structure and
`Cache-Control: no-store`. No provider messages, driver details, or sessions appear in errors.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Missing/malformed/invalid fields or JSON |
| 401 | INVALID_CREDENTIALS | Generic invalid email/password; no email-existence distinction |
| 405 | METHOD_NOT_ALLOWED | Use POST |
| 413 | REQUEST_TOO_LARGE | Body exceeds 16 KiB |
| 415 | UNSUPPORTED_MEDIA_TYPE | Use JSON |
| 429 | AUTH_RATE_LIMITED | Provider rate limit; retry later |
| 502 | AUTH_RESPONSE_INVALID | Malformed/incomplete/expired session, identity mismatch, token verification rejection, unexpected provider/configuration failure |
| 503 | AUTH_UNAVAILABLE | Auth network failure, timeout, or provider 5xx |
| 503 | ACCOUNT_PROVISIONING_REQUIRED | Verified Auth identity has no application account; contact support |
| 503 | ACCOUNT_UNAVAILABLE | Application database lookup/transaction failure |
| 500 | INTERNAL_ERROR | Sanitized unexpected error |

Provider `invalid_credentials`, `email_not_confirmed`, and `user_banned` rejections on 400/401/422
map to the identical 401 body: `{"code":"INVALID_CREDENTIALS","message":"Invalid email or password."}`.
Login accepts both Supabase error formats: unversioned responses use numeric `code` plus symbolic
`error_code` (for example, `{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}`),
while newer versioned responses use symbolic `code` plus `message`. When present, `error_code`
takes precedence; a null, malformed, or unknown value cannot fall back to a recognized `code`.
Only the allowlisted symbolic codes above on the allowed statuses map to 401. Provider messages
are never matched, logged, or returned. JSON with optional charset parameters is accepted;
non-JSON content types and malformed JSON still fail closed.

The previous implementation checked only symbolic `code`, so the numeric-code payload reproduced
the reported production 502. The JDK HTTP client returns non-2xx responses without throwing for
status alone; classification, rather than an early HTTP status exception or the global handler,
caused this failure. No live provider response body was captured during this fix. Supabase's
[error response implementation](https://github.com/supabase/auth/blob/master/internal/api/errors.go)
selects between API-version-specific formats; its
[password grant implementation](https://github.com/supabase/auth/blob/master/internal/api/token.go)
uses `invalid_credentials` for both unknown users and incorrect passwords.

Invalid API keys, CAPTCHA requirements, and unknown provider errors fail closed as 502, rather than
being mistaken for bad user credentials. Signup retains its existing error mapping.

## Identity and persistence

1. Send email/password to `/auth/v1/token?grant_type=password` with the existing publishable key.
2. Reuse signup's session validator: require valid identity fields, complete unexpired bearer
   session, then call authenticated `/auth/v1/user`. UUID and exact provider email must agree;
   anonymous identities are rejected. Unverified JWT decoding is never trusted.
3. Read `UserService.getById(verifiedUuid)` in its existing read-only transaction. No lookup by
   submitted email, account creation, profile update, password comparison, or password storage occurs.
4. Return the existing application account and allowlisted session only after lookup completes and
   expiry is rechecked. Missing accounts require the authorized operator recovery process in
   [signup documentation](SIGNUP.md#operator-assisted-reconciliation-runbook).

Auth calls retain the existing HTTPS configuration, no redirects, per-request full-body timeout,
64 KiB response cap, and no automatic retries. Safe `account.lookup` database diagnostics include
classifications and correlation IDs, never exception messages, SQL, credentials, or account values.
Sensitive DTO string representations are redacted. Preserve the existing logging suppression;
never enable HTTP body/header tracing, APM payload capture, or proxy token logging.

Supabase may issue a session before verification/database lookup fails. The backend withholds it
from the response and does not persist it or automatically revoke it. Login does not prove mailbox
ownership under the existing disabled-confirmation configuration. Browser token storage, general
route authentication, and application rate limiting remain unresolved separate work. See
[Supabase password sign-in](https://supabase.com/docs/reference/javascript/auth-signinwithpassword).

## Local test

Automated tests require no Supabase credentials:

```sh
cd backend
./mvnw verify
./mvnw -Ppostgres-it verify
```

The second command requires Docker and runs isolated PostgreSQL containers. HTTP tests use a local
stub server; no live Supabase or production database is contacted.

For a manual test, use an explicitly authorized **development** project with an existing Auth
identity and matching `public.users` UUID. Export the following privately using your IDE/environment
manager (Spring does not load `.env` automatically):

- `SPRING_PROFILES_ACTIVE=database`
- `SIP_AUTH_ENABLED=true`
- `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` for that project
- `SIP_DB_URL`, `SIP_DB_USERNAME`, `SIP_DB_PASSWORD` for its authorized database

The project must already have the reviewed users migration and account provisioned; startup never
initializes the schema. Start the backend with `./mvnw spring-boot:run`, then run the token-safe smoke
test below from the repository root. No frontend is required.

## Token-safe local and Render smoke test

Use an authorized existing test account. For local testing set:

```sh
export SIP_TEST_BASE_URL=http://localhost:8080
```

After a separately approved deployment, set the existing Render HTTPS URL instead:

```sh
export SIP_TEST_BASE_URL=https://YOUR-SERVICE.onrender.com
```

Keep Render's existing `prod`/`render` profile, Auth key/URL, JDBC credentials, TLS, `PORT`, and CORS
configuration. Run this same command in a private terminal. It prompts for credentials (password
hidden), sends JSON, and prints only status and allowlisted booleans/error code. It never prints or
writes sessions, bodies, or passwords; do not run with HTTP debug tracing or in a recorded console.

```sh
python3 - <<'PY'
import getpass
import json
import os
import urllib.error
import urllib.request

base = os.environ['SIP_TEST_BASE_URL'].rstrip('/')
if not (base.startswith('https://') or base in ('http://localhost:8080', 'http://127.0.0.1:8080')):
    raise SystemExit('Use HTTPS or the local loopback server.')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
opener = urllib.request.build_opener(NoRedirect)
payload = json.dumps({'email': input('Email: '), 'password': getpass.getpass('Password: ')}).encode()
request = urllib.request.Request(base + '/api/auth/login', data=payload,
                                 headers={'Content-Type': 'application/json'}, method='POST')
try:
    response = opener.open(request, timeout=35)
except urllib.error.HTTPError as error:
    response = error
except (urllib.error.URLError, TimeoutError):
    raise SystemExit('Request failed; inspect sanitized server diagnostics.') from None
with response:
    status = response.code
    try:
        body = json.loads(response.read(65537))
    except (ValueError, UnicodeError):
        raise SystemExit('Non-JSON response; body withheld.') from None
    if status == 200:
        session = body.get('session', {})
        print('HTTP', status, 'account_present=', bool(body.get('user', {}).get('id')),
              'session_complete=', all(session.get(key) for key in
              ('accessToken', 'refreshToken', 'tokenType', 'expiresIn', 'expiresAt')),
              'no_store=', response.headers.get('Cache-Control') == 'no-store')
    else:
        allowed = {'INVALID_REQUEST', 'INVALID_CREDENTIALS', 'METHOD_NOT_ALLOWED',
                   'REQUEST_TOO_LARGE', 'UNSUPPORTED_MEDIA_TYPE', 'AUTH_RATE_LIMITED',
                   'AUTH_RESPONSE_INVALID', 'AUTH_UNAVAILABLE', 'ACCOUNT_PROVISIONING_REQUIRED',
                   'ACCOUNT_UNAVAILABLE', 'INTERNAL_ERROR'}
        code = body.get('code') if isinstance(body, dict) else None
        print('HTTP', status, 'code=', code if code in allowed else 'UNRECOGNIZED')
PY
```

Expect HTTP 200 and all booleans true. Repeat with an incorrect password and an unknown email;
both must report HTTP 401 / `INVALID_CREDENTIALS`. Avoid repeated attempts that trigger rate limits.
To check malformed input without credentials:

```sh
curl --silent --show-error --request POST "$SIP_TEST_BASE_URL/api/auth/login" \
  --header 'Content-Type: application/json' --data '{}' --write-out '\nHTTP %{http_code}\n'
```

Expect HTTP 400 / `INVALID_REQUEST`. Do not create/delete production identities or alter production
rows to exercise failure cases; use the automated isolated tests for those cases.

## Verification record

Verified locally on November 1, 2026, with JDK 21 and isolated HTTP/PostgreSQL infrastructure:

| Command | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw spotless:apply verify` | 197 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 197 passed | 17 passed | BUILD SUCCESS |

Both runs had zero failures, errors, or skips and passed the formatting check. Existing signup,
account persistence, CORS, and production configuration checks remain green. The new persistence
test compares every account column before/after login and confirms missing accounts stay absent.
The initial sandboxed targeted test run could not bind loopback ports or attach Mockito's agent;
verification succeeded with those test permissions enabled. No live Supabase or Render smoke test
was performed, no production data/configuration was changed, and nothing was deployed.

Tracked/staged diff whitespace checks and untracked-file whitespace review passed. New local
links and smoke-test Python syntax were checked. Existing references to
`docs/development-decisions.md` and `docs/readiness-report.md` point to absent files; this ticket
follows the explicit login request and does not resolve broader architecture/product decisions.

### Incorrect-password regression fix

The initial-format invalid-credentials payload reproduced HTTP 502 before the fix for all three
tested JSON content types. After the login-only extraction fix, all 80 HTTP client tests passed,
including controller/service/client integration checks using a local Supabase stub. Regression
coverage includes both API error formats, charset parameters, repeated requests, unknown emails,
incorrect passwords, upstream/configuration failures, malformed bodies/codes, content-type
rejections, response/log sanitization, and no account or user-verification lookup on rejection.
Existing success, signup, timeout, and network-failure tests remain green.

Complete verification on November 1, 2026:

| Command | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw verify` | 226 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 226 passed | 17 passed | BUILD SUCCESS |

Zero failures, errors, or skips. Formatting and tracked/staged diff whitespace checks passed.
Only the Auth client, its HTTP tests, and this document changed. No live Supabase/Render request,
production database access, configuration change, commit, push, merge, or deployment was performed.
