# Organization profiles

This ticket implements authenticated organization reads and owner-authorized editing. Organization
self-registration is **not implemented**. Standard signup, login, `/api/users/me`, and browser
session handling retain their existing contracts. No frontend code changes are required.

## Persistence and relationships

[V002](db/migrations/V002__create_organizations.sql) adds only `public.organizations`:

| Column | Type | Required/default |
| --- | --- | --- |
| id | UUID, primary key | Generated UUID v4 |
| name | VARCHAR(255) | Required |
| description | TEXT | Nullable |
| website | VARCHAR(255) | Nullable |
| is_verified | BOOLEAN | Required, database default false |
| created_at | TIMESTAMPTZ | Required, database default NOW() |

PostgreSQL 13+ supplies `gen_random_uuid()` without an extension; it provides the same UUID v4
semantics as the DDD's `uuid_generate_v4()` without depending on Supabase extension search paths.
JPA generates UUID v4 IDs for inserts; SQL inserts can use the database default. Hibernate reads
verification and timestamp defaults back after insert. Neither column is updated by JPA.
`OrganizationProvisioningService.create(name, description, website)` is an internal transactional
creation boundary, **not** an HTTP endpoint or an account-assignment workflow. It creates no users.

`users.organization_id` remains a nullable UUID scalar in JPA and gains the validated
`users_organization_fkey` referencing `organizations.id`, with NO ACTION for delete/update.
No cascade, name uniqueness, role-association constraint, relationship table, or extra field is added.
Organization membership/following does not establish ownership. Existing STANDARD accounts with
null associations require no data migration. Non-null dangling associations prevent V002 from
committing and require separately reviewed operator reconciliation; no account is changed/deleted.

## Authentication and authorization

Endpoints use the existing `SIP_AUTH_ENABLED=true` and database-capable profile configuration.
`ProfileService.me` verifies exactly one RFC 6750 Bearer header with the existing Supabase Auth
client and loads the application account by verified UUID. No decoded claims, client role,
organization ID, name, or membership can establish editing authority. Each request verifies Auth;
provider availability is required. Services enforce authorization, including when called without MVC.

| Operation | Rule |
| --- | --- |
| Read by ID | Any authenticated identity with an application account |
| Read `/me` | Persisted ORGANIZATION role and non-null organization association |
| Edit by ID | Persisted ORGANIZATION role and association exactly equal to target UUID |
| STANDARD / ADMIN edit | 403, including when associated with the target |
| ORGANIZATION without association | 403 |
| ORGANIZATION editing another ID | 403 before existence lookup, including nonexistent IDs |

Successful reads/patches return 200 with `Cache-Control: no-store`. All responses expose only the
six fields below. No application account, email, token, or entity lifecycle property is serialized.

## Endpoints and examples

All requests require `Authorization: Bearer <ACCESS_TOKEN>`. These examples contain placeholders,
not credentials; avoid printing tokens in terminals, request logs, or shared screenshots.

```http
GET /api/organizations/a975e6c1-38b9-438a-a798-12741f117870
Authorization: Bearer <ACCESS_TOKEN>
Accept: application/json
```

```http
GET /api/organizations/me
Authorization: Bearer <ACCESS_TOKEN>
Accept: application/json
```

Both return the same shape (illustrative data):

```json
{
  "id": "a975e6c1-38b9-438a-a798-12741f117870",
  "name": "Habitat for Humanity",
  "description": "Helping families build safe, affordable homes.",
  "website": "https://www.habitat.org",
  "isVerified": false,
  "createdAt": "2026-11-11T18:00:00Z"
}
```

```http
PATCH /api/organizations/a975e6c1-38b9-438a-a798-12741f117870
Authorization: Bearer <ACCESS_TOKEN>
Content-Type: application/json
```

```json
{
  "name": "Habitat for Humanity Atlanta",
  "description": "Building homes and strengthening communities.",
  "website": "https://www.habitat.org"
}
```

The 200 response has the same six-field shape with these updated values and the original ID,
verification state, and creation time. Updates flush and commit in the service transaction before
MVC returns success. A failed flush/commit does not return a successful edit.

PATCH rules:

- Only `name`, `description`, and `website` are accepted. Unknown/protected fields cause 400,
  including `id`, `organizationId`, `role`, `isVerified`, and `createdAt`.
- Omitted fields remain unchanged; `{}` is an authorized no-op. Null clears description/website.
  Name cannot be null or blank. Empty description is valid. Empty website is invalid; use null.
- Name and website fit 255 Unicode code points, matching PostgreSQL VARCHAR semantics. TEXT has
  no invented character limit. Values are preserved without trimming; NUL and invalid Unicode
  surrogates are rejected. Non-string values are rejected instead of coerced.
- Websites must be absolute HTTP(S) URIs with a DNS/ASCII host or IP literal, no user credentials,
  and a valid optional port (1–65535). Paths, queries, fragments, localhost and IP hosts are allowed;
  Unicode hostnames must be supplied as punycode. No network request or domain verification occurs.
  The backend never fetches these URLs. Frontends should render text with React escaping.
- No optimistic concurrency/version column is added. Concurrent edits have last-writer behavior;
  clients should reload before editing. Rate limiting and a general request-body cap remain follow-up work.

## Errors

Errors are `{"code":"STABLE_CODE","message":"Safe message."}` with `Cache-Control: no-store`.
Raw database/provider exceptions, rejected values, and bearer tokens are never logged or returned.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Invalid JSON, fields, values, or UUID path |
| 401 | UNAUTHORIZED | Missing/malformed/duplicate header, invalid/expired token; `WWW-Authenticate: Bearer` |
| 403 | FORBIDDEN | Role, association, or target ownership denied |
| 404 | ORGANIZATION_NOT_FOUND | Authorized read target absent, or own linked record absent |
| 405 | METHOD_NOT_ALLOWED | Unsupported method |
| 415 | UNSUPPORTED_MEDIA_TYPE | PATCH requires application/json |
| 429 | AUTH_RATE_LIMITED | Existing Auth rate-limit convention |
| 502 | AUTH_RESPONSE_INVALID | Invalid upstream Auth response |
| 503 | AUTH_UNAVAILABLE | Existing Auth timeout/network/provider failure |
| 503 | ACCOUNT_PROVISIONING_REQUIRED | Verified identity lacks an application account |
| 503 | ACCOUNT_UNAVAILABLE | Existing account lookup/transaction failure |
| 503 | ORGANIZATION_UNAVAILABLE | Organization persistence/transaction failure |
| 500 | INTERNAL_ERROR | Sanitized unexpected failure |

MVC can reject malformed JSON, UUIDs, or media types before service authentication. A structurally
valid request with missing/invalid credentials is 401. Ownership is checked before organization
lookup for PATCH; an unrelated target never changes a denied response from 403 to 404.

## Frontend TypeScript contract

Use the existing Supabase session's current `access_token`; do not cache a separate organization
session or put privileged credentials in Vite. `VITE_API_BASE_URL` includes `/api` (default `/api`;
on Vercel with a separate backend, `https://YOUR-SERVICE.onrender.com/api`).

```ts
type OrganizationProfile = {
  id: string;
  name: string;
  description: string | null;
  website: string | null;
  isVerified: boolean;
  createdAt: string; // ISO-8601 UTC
};
type OrganizationPatch = {
  name?: string;
  description?: string | null;
  website?: string | null;
};

async function organizationRequest(
  accessToken: string,
  id: string = 'me',
  patch?: OrganizationPatch,
): Promise<OrganizationProfile> {
  const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '');
  const response = await fetch(`${base}/organizations/${encodeURIComponent(id)}`, {
    method: patch === undefined ? 'GET' : 'PATCH',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      Accept: 'application/json',
      ...(patch === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: patch === undefined ? undefined : JSON.stringify(patch),
    cache: 'no-store', credentials: 'omit', redirect: 'error',
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) throw new Error(`Organization request failed (${response.status})`);
  return response.json();
}
// organizationRequest(session.access_token) reads /me.
// organizationRequest(session.access_token, organizationId) reads by ID.
// organizationRequest(session.access_token, organizationId, { website: null }) clears the URL.
```

This is an integration example, not a new frontend API module. Production integration should
reuse the existing API client's response validation and safe code-to-message error mapping.

## Manual Supabase migration checklist

No SQL has been executed against Supabase by this task.

1. Review [V002](db/migrations/V002__create_organizations.sql) and confirm the target project and
   deployment authorization. In Supabase Dashboard, select the project, then **SQL Editor → New query**.
   V001 is already applied: do not edit or rerun it.
2. Before V002, check `SELECT count(*) FROM public.users WHERE organization_id IS NOT NULL;`
   and `SELECT to_regclass('public.organizations');`. Expect zero associations and no existing table.
   If either differs, stop and review schema/data drift. Do not clear associations automatically.
3. Execute the entire V002 file once as the database owner, including BEGIN/COMMIT. It deliberately
   fails on existing objects and adds a validated FK. On failure, issue ROLLBACK if the SQL Editor
   session remains in an aborted transaction; resolve the cause before retrying the unapplied version.
4. Run the verification queries below. Record project, version, source revision, operator and date.
5. Confirm the effective Render JDBC role's schema/table privileges and RLS strategy. The existing
   owner connection normally bypasses RLS. A restricted role requires USAGE on schema public,
   SELECT on users/organizations, and UPDATE on editable organization columns (or table UPDATE).
   Internal future provisioning also requires INSERT on organizations and users. UUID generation
   requires no sequence privilege. Table grants alone do not bypass RLS: a non-owner/non-bypass role
   also needs separately reviewed backend-only policies. Do not grant browser roles access or
   introduce public policies to solve backend permissions. No new credentials are required.
6. Deploy backend code only after the migration and JDBC permission review succeed.

```sql
SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'organizations'
ORDER BY ordinal_position;

SELECT conname, convalidated, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid IN ('public.organizations'::regclass, 'public.users'::regclass)
ORDER BY conname;
-- Expect organizations_pkey and validated users_organization_fkey -> organizations(id).

SELECT relrowsecurity FROM pg_class WHERE oid = 'public.organizations'::regclass;
-- Expect true.
SELECT * FROM pg_policies WHERE schemaname = 'public' AND tablename = 'organizations';
-- Expect no policies introduced by V002.
SELECT r.role_name, p.privilege,
       has_table_privilege(r.role_name, 'public.organizations', p.privilege) AS allowed
FROM (VALUES ('anon'), ('authenticated')) r(role_name)
CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'),
                   ('REFERENCES'), ('TRIGGER')) p(privilege);
-- Expect every allowed value false; inspect inherited grants if not.
SELECT grantee, privilege_type FROM information_schema.table_privileges
WHERE table_schema = 'public' AND table_name = 'organizations';
-- Confirm no PUBLIC/browser grants and the reviewed backend privileges.
SELECT count(*) AS dangling_associations FROM public.users u
LEFT JOIN public.organizations o ON o.id = u.organization_id
WHERE u.organization_id IS NOT NULL AND o.id IS NULL;
-- Expect zero. Do not paste account data/credentials into tickets.
```

## Manual Render / Vercel checklist

1. Keep existing `prod` or `render` profile, `SIP_AUTH_ENABLED=true`, Supabase URL/publishable key,
   JDBC URL/username/password, TLS settings, PORT and exact CORS origins. Never combine local and
   production profiles. No new variables, dependencies, services, or infrastructure are needed.
2. After V002, verify effective JDBC access as described above. Startup and `/api/health` do not
   prove table access: schema initialization/automatic migration remain disabled.
3. Deploy the reviewed backend revision through the team's normal authorized process. Inspect
   build/startup logs for the active profile and sanitized errors; do not enable SQL/header/body logs.
4. In an authorized development project with separately provisioned organization A/B managers,
   sign in through existing login, then send the samples above with current access tokens.
   Expect read/me 200, own PATCH 200, then GET showing persisted edits. B, STANDARD, ADMIN, and
   unassociated edits must return 403; missing/expired tokens return 401. Use automated isolated
   tests for destructive/invalid-FK cases. Do not create production accounts for testing automatically.
5. No Vercel redeployment is needed for this backend-only change. Future UI integration retains
   `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, and the API base including `/api`.
   Existing CORS already permits PATCH/Authorization/Content-Type; update exact allowed origins
   only if the frontend deployment origin changes. Never use a VITE database/service-role secret.

## Tests and current limitations

From `backend/`, with JDK 21:

```sh
./mvnw verify
./mvnw -Ppostgres-it verify
```

The latter requires Docker. Tests use mocks/local Auth stubs and isolated PostgreSQL 17 containers,
never live Supabase. Organization controller tests exercise the real service and ProfileService;
integration tests apply actual V001/V002, preserve a legacy account across V002, check database
defaults/FK/RLS, reload successful edits, prove role/organization isolation, and force a failed flush
to verify rollback. Existing auth/account tests remain regression coverage. Historical V001 tests
continue to test V001 alone; the new suite tests the additive migration chain.

No frontend changes, verification workflow, public creation endpoint, self-registration, listing,
following, membership management, events, RSVP, hours, messaging, or dashboard is implemented.
Existing managers must be provisioned through a separately authorized operator process. No
operator-assignment API or production data changes are part of this ticket.

## Signup integration assessment and follow-up tickets

Organization signup requires a separate ticket. It touches `SignupRequest`, `SignupService`,
`UserService`, retry matching, recovery documentation, frontend `Signup`/allowlisting/form/session
integration, and tests. Current retries explicitly require STANDARD and must not be broadened
without reviewing recovery and uncertain-commit behavior. No teammate work was overwritten.

Required implementation:

1. Approve a discriminated Standard/Organization signup contract, preserving current Standard
   clients. Accept only an allowlisted account-kind selector and organization profile input;
   reject ADMIN, arbitrary roles, existing organization IDs, and verification/ownership fields.
2. Reuse existing Auth signup and authenticated identity verification exactly once. Standard
   creates only STANDARD with null association. Organization calls a new proxied PostgreSQL
   provisioning boundary that inserts a new unverified organization and ORGANIZATION user
   association in **one transaction**; reuse this internal creation service with REQUIRED joining.
   Never match an existing organization by name or let a caller claim one by ID.
3. Preserve session response shape and release it only after commit. Supabase Auth and PostgreSQL
   cannot share a transaction: withhold sessions on provisioning failure, never automatically
   delete Auth identities, and keep operator-assisted reconciliation. Define recovery of the exact
   verified UUID/account-kind/new-organization outcome for transient errors and uncertain commits
   before adding retries, so retries cannot create duplicate organizations or promote Standard users.
4. Add account-kind UI and API types, then test both paths, atomic rollback, duplicate/uncertain
   commits, partial failures, session adoption and refresh regressions. Login remains shared.
5. Separate tickets must define organization following (D04/D11; do not repurpose organization_members),
   event management/RSVP (event_registrations), verification authority, and broader ADMIN behavior
   (D02). The referenced `docs/development-decisions.md` register is absent in this checkout.
   This request resolves only authenticated profile visibility and ORGANIZATION ownership editing.

## Change inventory

Added under `src/main/java/edu/gatech/sip6321/organization/`:
`Organization.java`, `OrganizationRepository.java`, `OrganizationProvisioningService.java`,
`OrganizationService.java`, `OrganizationPatch.java`, `OrganizationResponse.java`,
`OrganizationController.java`, `OrganizationFailure.java`, and `OrganizationExceptionHandler.java`.

Added under `src/test/java/edu/gatech/sip6321/organization/`:
`OrganizationServiceTest.java`, `OrganizationControllerTest.java`, and `OrganizationPersistenceIT.java`.
Also added this guide and `db/migrations/V002__create_organizations.sql`.
Updated root `README.md`, `backend/ACCOUNTS.md`, and `docs/api.md`, `docs/architecture.md`,
`docs/data-model.md`, and `docs/security.md`. Existing auth, frontend, V001, build/configuration,
and CI sources are unchanged.

## Verification record — November 11, 2026

Executed with Temurin JDK 21.0.5 from `backend/`:

| Command | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw spotless:apply -Dtest=OrganizationControllerTest,OrganizationServiceTest test` | 37 passed | Not requested | BUILD SUCCESS |
| `./mvnw spotless:apply verify` | 298 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 298 passed | 27 passed | BUILD SUCCESS |

All final runs had zero failures, errors, or skips. Formatting and packaging passed. The PostgreSQL
suite includes 9 new organization tests and 18 existing account/auth regression tests; 37 new
ordinary tests cover organization controllers/services. An initial sandboxed attempt could not
attach Mockito or use loopback test infrastructure; final verification ran with local test
permissions. An initial unrestricted run exposed one test mock-restubbing error, corrected before
the successful runs above. Docker Desktop was started with authorization for disposable containers.

Tracked/staged diff whitespace checks, untracked-file whitespace review, new local documentation
links, and changed-file credential review passed. No frontend code changed, so frontend checks
were not rerun. No live Supabase/Auth, Render or Vercel smoke tests, live migration, production
account provisioning, deployment, credential changes, commit, push or merge were performed.
The referenced development-decision register remains absent; broader signup/following/ADMIN
policies require their separate tickets. Concurrent edits retain last-writer behavior, and
production JDBC grants/RLS access must be confirmed manually before deployment.
