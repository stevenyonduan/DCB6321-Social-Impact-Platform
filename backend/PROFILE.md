# Authenticated user profile

`GET /api/users/me` accepts `Authorization: Bearer <supabase_access_token>` and no body.
It is enabled by the existing `SIP_AUTH_ENABLED=true` setting and database-capable profile,
alongside signup/login. No migration, dependency, new Render variable, CORS change, or production
configuration change is required. Profile editing and frontend integration are outside this ticket.

HTTP 200 returns a plain application profile object with `Cache-Control: no-store`:

```json
{
  "id": "b6034174-d377-4ad4-b96a-6777660143e6",
  "email": "student@example.com",
  "role": "STANDARD",
  "firstName": "Test",
  "lastName": "Student",
  "bio": null,
  "avatarUrl": null,
  "organizationId": null,
  "createdAt": "2026-11-01T18:00:00Z"
}
```

All nine fields come from `public.users`. Nullable profile fields are present as JSON null;
`createdAt` is an ISO-8601 UTC instant. All existing account roles can read their own account.
No password, session, token, entity lifecycle state, or internal persistence field is returned.

## Authentication and account lookup

The service requires exactly one Authorization header with the Bearer scheme (case insensitive),
one or more spaces, and a nonempty RFC 6750 credential. Combined/duplicate headers, controls,
extra credentials, and headers over 16,391 characters are rejected before contacting Auth.
The servlet container can reject oversized headers earlier according to its own limits.

The existing `HttpSupabaseAuthClient` verifies the token with `GET /auth/v1/user` against the
configured HTTPS Supabase origin, using the existing publishable key. It reuses the bounded
HTTP exchange, full-body timeout, response cap, and no-redirect policy used by signup/login.
There is no existing authentication filter; this endpoint makes exactly one verification request.
Supabase checks validity/expiry; the application never authorizes decoded JWT claims. The verified
UUID alone selects `UserService.getById` in its existing read-only transaction. Request IDs,
emails, roles, query parameters, or bodies cannot select another account. Application email drift
does not change the UUID lookup. The endpoint never provisions, reconciles, edits, or deletes rows.

## Errors

Errors use `{"code":"STABLE_CODE","message":"Safe message."}` and `Cache-Control: no-store`.
Authentication failures also include `WWW-Authenticate: Bearer`.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 401 | UNAUTHORIZED | Missing/malformed Bearer header, rejected/expired token, or anonymous identity |
| 429 | AUTH_RATE_LIMITED | Existing provider rate-limit convention |
| 502 | AUTH_RESPONSE_INVALID | Malformed upstream identity/response or unexpected provider/configuration rejection |
| 503 | AUTH_UNAVAILABLE | Supabase 5xx, network failure, or timeout |
| 503 | ACCOUNT_PROVISIONING_REQUIRED | Verified identity has no application account; existing login convention |
| 503 | ACCOUNT_UNAVAILABLE | Database query/connection/transaction failure |
| 500 | INTERNAL_ERROR | Sanitized unexpected server error |

No upstream body, SQL, credentials, token, or exception message is included. Missing accounts use
login's existing provisioning convention rather than 404; follow the authorized recovery process
in [SIGNUP.md](SIGNUP.md#operator-assisted-reconciliation-runbook), outside this read-only endpoint.
Verification depends on Supabase availability and makes a network request on each profile read.

## Local verification and manual test

From the repository root:

```sh
cd backend
./mvnw spotless:apply verify
./mvnw -Ppostgres-it verify
```

The second command requires Docker and uses disposable PostgreSQL only. Auth HTTP calls use a local
stub or mocks. No Supabase credentials are needed for automated tests.

For manual testing, use an explicitly authorized development project with an existing Auth identity
and matching application account. Configure private environment/IDE values as described in
[SIGNUP.md](SIGNUP.md): `SPRING_PROFILES_ACTIVE=database`, `SIP_AUTH_ENABLED=true`,
`SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `SIP_DB_URL`, `SIP_DB_USERNAME`, and `SIP_DB_PASSWORD`.
Spring does not load `.env` files automatically; schema initialization remains disabled.
Start with `./mvnw spring-boot:run` from `backend/`. In a separate private terminal at repository root:

```sh
SIP_TEST_BASE_URL=http://127.0.0.1:8080 python3 backend/scripts/profile-smoke.py
```

Enter the existing account's email/password when prompted. The password is hidden. The script
obtains `session.accessToken` from login and calls `/api/users/me`; it prints statuses and contract
checks only. Access/refresh tokens, profile data, and passwords are never printed or written to
a file or passed as command-line arguments. It refuses redirects and requires HTTPS except for
local loopback. Use a private terminal without HTTP tracing. Expect login/profile HTTP 200 and
`own_profile_and_contract_valid=True`, followed by two HTTP 401 checks with `passed=True`.
A nonzero exit indicates failure; raw error bodies are withheld.

## Render smoke test after separately approved deployment

No deployment or production request is performed by this ticket. Keep existing Render settings.
After deployment, run from the repository root with an authorized existing test account:

```sh
SIP_TEST_BASE_URL=https://YOUR-SERVICE.onrender.com python3 backend/scripts/profile-smoke.py
```

Expected results are the same as locally. Login is the only POST; subsequent requests are reads.
Do not create or modify production accounts to exercise missing-account, database, or upstream
failure cases; automated tests cover them in isolation. To check malformed authorization separately:

```sh
curl --silent --show-error --include \
  --header 'Authorization: Basic invalid' \
  https://YOUR-SERVICE.onrender.com/api/users/me
```

Expect HTTP 401, `Cache-Control: no-store`, and `UNAUTHORIZED`. No real token is used in this command.

## Change inventory

- Added `auth/ProfileController.java`, `ProfileService.java`, `ProfileResponse.java`,
  `ProfileFailure.java`, and `ProfileExceptionHandler.java` under `backend/src/main/java/edu/gatech/sip6321/`.
- Extended `auth/SupabaseAuthClient.java` and `HttpSupabaseAuthClient.java` with trusted access-token
  verification; registered the service in `SignupConfiguration.java`.
- Added `auth/ProfileControllerTest.java`; extended `auth/HttpSupabaseAuthClientTest.java`,
  `auth/SignupConfigurationTest.java`, and `account/UserPersistenceIT.java` under the matching test tree.
- Added `backend/scripts/profile-smoke.py` and this document; updated `README.md`, `backend/LOGIN.md`,
  `docs/api.md`, `docs/architecture.md`, and `docs/security.md`.

## Verification record

Verified locally on November 1, 2026 with JDK 21:

| Command (from backend/) | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw spotless:apply verify` | 251 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 251 passed | 18 passed | BUILD SUCCESS |

Both commands had zero failures, errors, or skips and passed formatting. Existing signup/login
tests passed. A local HTTP stub test sends login's returned access token through the profile
controller and verifies one Auth lookup per profile request. The PostgreSQL test compares all
account columns before/after reads and confirms missing accounts stay absent. Controller coverage
includes field mapping, spoofed identity parameters, malformed/duplicate/missing/oversized headers,
invalid credentials, safe error responses, database failures, and CORS preflight. Auth HTTP tests
cover rejected/expired tokens, malformed/anonymous identities, timeouts, and network/provider outages.

The smoke script passed syntax and a mocked login/profile/negative-request flow without exposing
credentials or making network calls. Tracked/staged diff whitespace checks, untracked-file
whitespace review, changed-file credential review, and new local documentation links passed.
Existing links to the absent `docs/development-decisions.md` and `docs/readiness-report.md` remain
unresolved. Initial sandboxed tests could not attach Mockito or bind loopback ports; verification
succeeded with test permissions enabled. No live Supabase or Render test was performed, production
data/settings were untouched, and nothing was committed, pushed, merged, or deployed.
