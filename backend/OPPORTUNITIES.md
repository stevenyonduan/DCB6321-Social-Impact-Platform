# Opportunity publishing API

This ticket implements immediate publication and authenticated organization-scoped listing of
volunteer opportunities. It reuses Supabase Auth identity verification, persisted application
roles, `users.organization_id`, and the existing organization model. No frontend code changes,
production migration, deployment, or new infrastructure are included.

## Approved PR #19 extensions

The owner approved future-only publishing/upcoming-only listing and INTERNAL/EXTERNAL registration
configuration as extensions to the original DDD. They supersede the initial PR's permissive past
publication and inclusive listing policy. The original DDD has ten event columns; the two
registration columns below are newly approved, not a transcription of that document.

The owner confirms V003 has **not** been applied to Supabase. This unmerged PR updates V003 in
place; no V004 is created. No live database was contacted to make that determination. If another
environment has already applied an older V003, stop and review its migration history rather than
rerunning/replacing that applied version or dropping its table.

## Schema and migration

[V003__create_events.sql](db/migrations/V003__create_events.sql) creates `public.events` after
V001/V002, which remain unchanged. Paste the **entire V003 file**, including BEGIN/COMMIT, into
Supabase SQL Editor only after the operator checks below. No automatic migration runs at startup.

| Column | PostgreSQL type | Requirement |
| --- | --- | --- |
| id | UUID | Primary key; SQL default gen_random_uuid() |
| organization_id | UUID | Required FK organizations(id) |
| creator_id | UUID | Required FK users(id) |
| title | VARCHAR(255) | Required |
| description | TEXT | Required |
| image_url | VARCHAR(255) | Nullable |
| event_start | TIMESTAMPTZ | Required |
| event_end | TIMESTAMPTZ | Required |
| location | VARCHAR(255) | Required |
| created_at | TIMESTAMPTZ | Required; database default NOW() |
| registration_type | VARCHAR(20) | Newly approved; NOT NULL, default INTERNAL |
| registration_url | TEXT | Newly approved; nullable only for INTERNAL |

JPA generates UUID v4 IDs, as for organizations; direct SQL inserts can use the SQL UUID default.
Hibernate reads the database-generated created_at back after insert. The organization and creator
UUIDs are scalar entity properties backed by foreign keys, following the existing account model.
Both foreign keys use ON UPDATE/DELETE NO ACTION. No cascades, status, or capacity are added.
The existing `(organization_id,event_start,id)` index supports organization equality, the upcoming
start-time range predicate, and stable ordering; no index change is necessary.
`events_registration_type_check` restricts the type to INTERNAL/EXTERNAL;
`events_registration_url_check` requires INTERNAL URLs to be null and EXTERNAL URLs to be non-null
with at least one non-whitespace character (PostgreSQL POSIX whitespace). URL structure is additionally
validated by the API. These CHECK constraints apply to direct SQL as well as API writes.
V003 enables RLS, revokes all PUBLIC/anon/authenticated privileges, and creates no browser policies.
Nonblank text and end-after-start are API validation rules, not additional SQL CHECK constraints.

## Identity and permissions

Enable the existing `SIP_AUTH_ENABLED=true` with a database-capable profile (`database`, `prod`,
or `render`). Every operation reuses `ProfileService.me`: exactly one valid Bearer header,
Supabase authenticated user lookup, then application-account lookup by that verified UUID.
Missing, invalid, expired, duplicate, or malformed credentials fail closed. Identity is not taken
from unverified JWT claims, user metadata, query parameters, or request fields.

Publishing requires persisted role ORGANIZATION and a non-null association to an existing
organization. The service assigns organizationId from that association and creatorId from the
verified application account. STANDARD and ADMIN cannot publish, including associated ADMINs.
There is no endpoint accepting a target organization for publication. Membership/following never
grants publishing rights. Both verified and unverified organizations may publish: `is_verified`
is separate from ownership and no verification prerequisite has been approved.

Any authenticated application account (including STANDARD, ORGANIZATION, and ADMIN) may list a
specified organization's opportunities, consistent with existing organization-profile visibility.
Responses intentionally expose event creator UUIDs, as authorized in this ticket, but no user
emails or account profile details. Auth availability is required on every request.

Publishing uses a service transaction and saveAndFlush. Commit completes before a 201 can be
returned; failed inserts/flushes/commits roll back and return sanitized errors.

## Endpoints and examples

All timestamps and identifiers below are illustrative. Every request requires a current Supabase
access token. Never print real tokens in logs or shared output.

```http
POST /api/organizations/me/opportunities
Authorization: Bearer <ACCESS_TOKEN>
Content-Type: application/json
Accept: application/json
```

```json
{
  "title": "Community Park Cleanup",
  "description": "Help clean up the park with our volunteer team.",
  "eventStart": "2027-01-16T09:00:00-05:00",
  "eventEnd": "2027-01-16T12:00:00-05:00",
  "location": "Piedmont Park, Atlanta, GA",
  "imageUrl": null,
  "registrationType": "INTERNAL",
  "registrationUrl": null
}
```

201 Created, `Cache-Control: no-store`:

```json
{
  "id": "3a5b0fd2-7d4a-4bd9-8d71-dca84dd08792",
  "organizationId": "a975e6c1-38b9-438a-a798-12741f117870",
  "creatorId": "943fba2c-cc41-457d-b2b3-3d376aa3a992",
  "title": "Community Park Cleanup",
  "description": "Help clean up the park with our volunteer team.",
  "imageUrl": null,
  "eventStart": "2027-01-16T14:00:00Z",
  "eventEnd": "2027-01-16T17:00:00Z",
  "location": "Piedmont Park, Atlanta, GA",
  "createdAt": "2026-11-12T15:00:00Z",
  "registrationType": "INTERNAL",
  "registrationUrl": null
}
```

For an EXTERNAL opportunity, send the same endpoint with (illustrative future dates):

```json
{
  "title": "Habitat Build Day",
  "description": "Help build homes with our team.",
  "location": "Atlanta, GA",
  "eventStart": "2027-01-23T08:00:00-05:00",
  "eventEnd": "2027-01-23T15:00:00-05:00",
  "imageUrl": null,
  "registrationType": "EXTERNAL",
  "registrationUrl": "https://www.example.org/volunteer/register"
}
```

The 201 response and every subsequent list item include the saved registration fields:

```json
{
  "id": "608581ea-0a4a-4c6b-a334-92dcfa539c79",
  "organizationId": "a975e6c1-38b9-438a-a798-12741f117870",
  "creatorId": "943fba2c-cc41-457d-b2b3-3d376aa3a992",
  "title": "Habitat Build Day",
  "description": "Help build homes with our team.",
  "location": "Atlanta, GA",
  "eventStart": "2027-01-23T13:00:00Z",
  "eventEnd": "2027-01-23T20:00:00Z",
  "imageUrl": null,
  "createdAt": "2026-11-12T15:00:00Z",
  "registrationType": "EXTERNAL",
  "registrationUrl": "https://www.example.org/volunteer/register"
}
```

Choose dates strictly after the backend clock when trying these examples. They are not permanent
future fixtures. Omitting registrationType defaults to INTERNAL; registrationUrl must then be
absent/null. Explicit null, unknown, differently cased, or non-string types are rejected.

No Location header is emitted: this ticket has no individual-event GET resource. The creation
response contains the complete saved event; reload through the list endpoint.

```http
GET /api/organizations/a975e6c1-38b9-438a-a798-12741f117870/opportunities?page=0&size=20
Authorization: Bearer <ACCESS_TOKEN>
Accept: application/json
```

200 OK, `Cache-Control: no-store`. The list is a paginated object, with each item using exactly
the same response shape as creation. For an organization with no upcoming opportunities:

```json
{"items": [], "page": 0, "size": 20, "hasNext": false}
```

A nonempty response has `items: [<complete opportunity object above>]`. There is no total count.
Only the requested organization's events are queried. Unknown organizations return 404 rather
than an empty list. Malformed UUIDs and invalid pagination values return 400.

## Pagination, scheduling, and validation

- Zero-based `page` defaults to 0, allowed 0–10000. `size` defaults to 20, allowed 1–100.
  Reads use a database Slice (at most size + 1 rows fetched); no full-table result or count query.
  The maximum offset is 1,000,000. Beyond the available rows, items is empty and hasNext is false.
- The PostgreSQL query is organization-scoped and uses `event_start > :now` **before** applying
  offset/limit, ordered by `event_start ASC, id ASC`. INTERNAL and EXTERNAL appear together.
  Past events and events starting exactly at the cutoff are excluded. Historical rows remain
  stored; there is no deletion job and no historical retrieval endpoint in this ticket.
- Each list request captures the injected server Clock once. It floors that instant to microseconds
  for the SQL parameter, avoiding JDBC rounding away a valid next-microsecond event. Because stored
  starts have microsecond precision, `start > floor(now)` and `start > now` select the same rows.
  The same cutoff applies to the entire Slice (including its extra hasNext row).
- Each page request captures a new instant. Pagination is stable for unchanged data and cutoff;
  time passing or concurrent inserts can shift offset pages. There is no cross-request snapshot
  guarantee. Historical retrieval and cursor/snapshot pagination remain separate features.
- Publishing captures the injected backend `Clock.systemUTC()` after account/organization checks.
  A normalized start must be strictly later than that full-precision clock instant, including for
  direct service calls. A start equal to now is rejected; no client currentTime is accepted.
  A start only one nanosecond later can truncate to the current/past microsecond and is rejected;
  the next representable microsecond is accepted if still future when the service validates it.
  No minimum scheduling lead time is introduced. Events may age between publication and listing.
- Only title, description, location, eventStart, eventEnd, imageUrl, registrationType, registrationUrl
  are accepted. Unknown fields, including organizationId, creatorId, id, createdAt, role, status,
  and client-provided currentTime, return 400. Required fields cannot be missing/null; JSON strings
  are required without scalar coercion. The sole omission default is registrationType=INTERNAL.
- Title, description, and location must be nonblank. Title, location, and optional imageUrl fit
  255 Unicode code points (PostgreSQL VARCHAR semantics). Description has no invented length cap.
  Text is preserved without trimming; NUL and unpaired Unicode surrogates are rejected.
- Start/end must be ISO-8601 date-times with an explicit offset or Z, within PostgreSQL's finite
  timestamp range (UTC -4713-11-24 inclusive to +294277-01-01 exclusive, proleptic ISO years;
  see the [PostgreSQL timestamp range definitions](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/include/datatype/timestamp.h)).
  Local date-times, numbers, infinity, and invalid dates are rejected.
  Values normalize to UTC and truncate sub-microsecond precision before validation/storage.
  End must be strictly later than start at the stored microsecond precision. This time-order rule
  is explicitly adopted by this ticket, not claimed as a formal DDD requirement.
- Responses serialize Instant values as ISO-8601 UTC strings ending in Z, with fractional seconds
  when present. PostgreSQL stores instants as TIMESTAMPTZ, not the original textual offset.
- imageUrl may be omitted/null; when supplied it must be an absolute HTTP(S) URI with a host,
  no user credentials, and an optional port in 1–65535. Empty or relative URLs are invalid.
  Unicode DNS names must use punycode. The API never fetches or verifies image bytes/availability,
  uploads files, or configures Storage. A URL is a reference only; D06 Storage policy remains open.

## Registration configuration and external destinations

- INTERNAL: registrationUrl must be absent or null. Any non-null value, including an empty string,
  is rejected. Omitted registrationType defaults to INTERNAL in JSON, Java's original six-argument
  request constructor, and the SQL default. Explicit null/invalid types never silently default.
- EXTERNAL: registrationUrl is required, nonblank, and an absolute HTTP(S) URI with an ASCII DNS
  host or IP literal, no embedded credentials, and an optional numeric port in 1–65535. HTTPS is
  recommended; HTTP is permitted for compatibility. Empty ports, relative/protocol-relative URLs,
  unsafe schemes, NUL/unpaired surrogates, controls, line/paragraph separators, bidi/format characters, Unicode noncharacters,
  and invalid UTF-8 escapes are rejected. Controls/format characters in decoded URI components
  (including percent-encoded CR/LF) are also rejected. Unicode hostnames must use punycode; valid
  Unicode paths are allowed. Values are preserved without trimming and have no VARCHAR(255) cap
  because registration_url is TEXT. Image URL validation remains separate and unchanged.
- The backend never fetches/visits/scrapes URLs or verifies ownership, safety of the destination,
  or registration completion. The link is untrusted organization-supplied data and grants no
  account/volunteer access. Both registration types use identical publication authorization.
- Future frontend: display the destination domain clearly, require a deliberate click, use safe
  external-link handling (`target="_blank"` with `rel="noopener noreferrer"` where appropriate),
  and never redirect automatically on opportunity load. Do not append access tokens, personal
  information, or other sensitive user data to destination URLs. Avoid leaking sensitive referrers.
  A link click is not a confirmed registration. INTERNAL may later show “Sign Up”; EXTERNAL may
  show “Register on Organization Site”. No buttons/UI, RSVP endpoints, event registration rows,
  or external-provider integration are implemented by this update.

## Errors

All mapped errors use `{"code":"STABLE_CODE","message":"Safe message."}` and no-store.
Rejected values, SQL errors, tokens, and internal stacks are not returned or logged by this API.
MVC may reject malformed JSON/path/query/media types before service authentication, as with the
existing organization API. Structurally valid unauthenticated requests return 401.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Invalid body, unknown field, value, timestamp, UUID, or pagination |
| 400 | EVENT_START_NOT_FUTURE | Normalized start is not strictly after the server clock |
| 400 | INVALID_REGISTRATION | Invalid type, type/URL combination, or external destination |
| 401 | UNAUTHORIZED | Missing/malformed/invalid/expired Bearer; WWW-Authenticate: Bearer |
| 403 | FORBIDDEN | Publishing role/association denied |
| 404 | ORGANIZATION_NOT_FOUND | Requested or associated organization absent |
| 405 | METHOD_NOT_ALLOWED | Unsupported method |
| 415 | UNSUPPORTED_MEDIA_TYPE | POST requires application/json |
| 429 | AUTH_RATE_LIMITED | Existing upstream Auth rate-limit response |
| 502 | AUTH_RESPONSE_INVALID | Existing invalid upstream Auth response |
| 503 | AUTH_UNAVAILABLE | Existing upstream Auth failure |
| 503 | ACCOUNT_PROVISIONING_REQUIRED | Verified identity has no application account |
| 503 | ACCOUNT_UNAVAILABLE | Application account lookup failed |
| 503 | OPPORTUNITY_UNAVAILABLE | Database/transaction failure |
| 500 | INTERNAL_ERROR | Sanitized unexpected failure |

## Frontend TypeScript contract

```ts
type PublishOpportunityRequest = {
  title: string;
  description: string;
  location: string;
  eventStart: string; // ISO-8601 with offset or Z
  eventEnd: string;
  imageUrl?: string | null;
  registrationType?: 'INTERNAL' | 'EXTERNAL'; // omitted => INTERNAL; explicit null is invalid
  registrationUrl?: string | null; // required for EXTERNAL, absent/null for INTERNAL
};
type Opportunity = {
  id: string;
  organizationId: string;
  creatorId: string;
  title: string;
  description: string;
  location: string;
  imageUrl: string | null;
  eventStart: string; // ISO-8601 UTC
  eventEnd: string;
  createdAt: string;
  registrationType: 'INTERNAL' | 'EXTERNAL';
  registrationUrl: string | null;
};
type OpportunityPage = {
  items: Opportunity[];
  page: number;
  size: number;
  hasNext: boolean;
};

const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '');
// Existing Supabase session access_token; reuse the app's API/error handling in a future UI ticket.
const response = await fetch(`${base}/organizations/me/opportunities`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${session.access_token}`,
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
  body: JSON.stringify(request), // PublishOpportunityRequest
  cache: 'no-store', credentials: 'omit', redirect: 'error',
});
// Check response.ok before reading an Opportunity; map stable error codes for display.
// GET `${base}/organizations/${encodeURIComponent(organizationId)}/opportunities?page=0&size=20`
// uses the same Bearer/Accept headers and returns OpportunityPage.
```

## Final V003 SQL for manual review

This is the complete current migration, mirrored from [V003](db/migrations/V003__create_events.sql).
Apply it once only after reviewing the procedure below; V001/V002 are unchanged.

```sql
-- Updated in unmerged PR #19: V003 has not been applied to Supabase.
-- Apply manually once after V001/V002. Never rerun or edit applied migrations.
-- NO ACTION preserves related records until deletion/update workflows are approved.
BEGIN;

CREATE TABLE public.events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    creator_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    image_url VARCHAR(255),
    event_start TIMESTAMPTZ NOT NULL,
    event_end TIMESTAMPTZ NOT NULL,
    location VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Newly approved PR #19 extensions, not original DDD columns.
    registration_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    registration_url TEXT,
    CONSTRAINT events_registration_type_check CHECK (registration_type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT events_registration_url_check CHECK (
        (registration_type = 'INTERNAL' AND registration_url IS NULL)
        OR (registration_type = 'EXTERNAL' AND registration_url IS NOT NULL
            AND registration_url ~ '[^[:space:]]')
    ),
    CONSTRAINT events_pkey PRIMARY KEY (id),
    CONSTRAINT events_organization_fkey FOREIGN KEY (organization_id)
        REFERENCES public.organizations (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT events_creator_fkey FOREIGN KEY (creator_id)
        REFERENCES public.users (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- Supports organization-scoped, stable chronological pagination without a full-table sort.
CREATE INDEX events_organization_start_id_idx ON public.events (organization_id, event_start, id);
ALTER TABLE public.events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.events FROM PUBLIC, anon, authenticated;
-- No browser policies. Review backend JDBC grants and RLS access separately.
COMMIT;
```

## Manual Supabase procedure and SQL verification

1. Confirm the intended project and source revision. V001 and V002 must already be applied.
   Do not rerun/edit them. Check `SELECT to_regclass('public.events');` before V003: expect null.
   If events already exists or any prior schema differs from V001/V002, stop and review drift with
   the team. V003 deliberately fails on existing objects; it does not repair or alter existing rows.
2. Review V003, including its NO ACTION FKs, then paste the **whole SQL from
   [V003](db/migrations/V003__create_events.sql)** into SQL Editor → New query and execute once as
   the database owner. It is transactional and preserves existing users/organizations.
   If it fails, ROLLBACK an aborted session and inspect the cause before retrying the unapplied file.
3. Run these read-only checks and record the version, operator, date, and source revision:

```sql
SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema='public' AND table_name='events'
ORDER BY ordinal_position;

SELECT conname, convalidated, pg_get_constraintdef(oid)
FROM pg_constraint WHERE conrelid='public.events'::regclass ORDER BY conname;
-- Expect events_pkey, validated organization/creator FKs (NO ACTION),
-- events_registration_type_check and events_registration_url_check.
SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
FROM information_schema.columns WHERE table_schema='public' AND table_name='events'
AND column_name IN ('registration_type','registration_url');
-- registration_type: character varying(20), NOT NULL, default INTERNAL.
-- registration_url: text, nullable.
SELECT registration_type, count(*) FROM public.events GROUP BY registration_type;
SELECT count(*) AS invalid_registration_rows FROM public.events
WHERE registration_type NOT IN ('INTERNAL','EXTERNAL') OR registration_type IS NULL
   OR (registration_type='INTERNAL' AND registration_url IS NOT NULL)
   OR (registration_type='EXTERNAL' AND
       (registration_url IS NULL OR registration_url !~ '[^[:space:]]'));
-- Expect zero invalid rows; do not insert production test records for these checks.

SELECT relrowsecurity FROM pg_class WHERE oid='public.events'::regclass;
-- true
SELECT * FROM pg_policies WHERE schemaname='public' AND tablename='events';
-- No policies introduced.
SELECT grantee, privilege_type FROM information_schema.table_privileges
WHERE table_schema='public' AND table_name='events';
-- No PUBLIC, anon, authenticated grants.
SELECT r.role_name, p.privilege,
       has_table_privilege(r.role_name, 'public.events', p.privilege) AS allowed
FROM (VALUES ('anon'),('authenticated')) r(role_name)
CROSS JOIN (VALUES ('SELECT'),('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE'),
                   ('REFERENCES'),('TRIGGER')) p(privilege);
-- All false; inspect inherited grants if different.

-- Substitute the actual effective JDBC role name, never a password:
SELECT has_schema_privilege('YOUR_BACKEND_ROLE','public','USAGE') AS schema_usage,
       has_table_privilege('YOUR_BACKEND_ROLE','public.users','SELECT') AS users_read,
       has_table_privilege('YOUR_BACKEND_ROLE','public.organizations','SELECT') AS orgs_read,
       has_table_privilege('YOUR_BACKEND_ROLE','public.events','SELECT') AS events_read,
       has_table_privilege('YOUR_BACKEND_ROLE','public.events','INSERT') AS events_insert;
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname='YOUR_BACKEND_ROLE';
SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='events';
```

4. Confirm backend schema USAGE, users/organizations SELECT, and events SELECT/INSERT. Existing
   table grants on other tables do not automatically grant events access. Table owner/superuser/
   BYPASSRLS connections normally bypass RLS; restricted JDBC roles require separately reviewed
   backend-only RLS policies as well as grants. Do not solve access by granting browser roles or
   creating public policies. No UUID sequence grant is needed. Review any required backend-only
   grants using the team's existing database-role process; they are not automatically applied here.
   A health response or has_table_privilege alone does not prove effective RLS access. Validate
   create/read using the deployed API in an authorized development project after deployment.

## Manual Render and Vercel deployment

1. Apply V003 and confirm effective JDBC grants/RLS access **before** deploying backend code.
2. No new environment variables or services are needed. Keep the existing prod/render profile,
   SIP_AUTH_ENABLED=true, SUPABASE_URL/publishable key, JDBC URL/username/password, database TLS,
   PORT, and exact CORS origins. Never combine local and prod profiles or enable schema generation.
3. Run verification below, review the code, and deploy the reviewed backend revision through the
   team's normal Render process. Do not enable SQL/bind/body/Authorization logging.
4. With an authorized development ORGANIZATION account already associated with an organization,
   obtain a current token through existing login. Send POST and expect 201 with generated id,
   creatorId, organizationId, createdAt, registrationType, registrationUrl. Test both INTERNAL and
   EXTERNAL with future dates. GET that organization and verify both IDs/configurations appear; repeat
   GET to verify persistence. GET a separate empty organization and verify it does not appear.
   STANDARD/ADMIN publication must be 403; missing/expired token 401. Past/equal starts and invalid
   registration combinations must be 400 without new rows. Advancing test clocks/historical fixture
   insertion belongs only in disposable tests. Avoid production test data.
   Health checks alone do not exercise database permissions. No accounts are provisioned by this API.
5. Vercel needs no change or redeployment for this backend ticket. A future frontend uses the
   current Supabase bearer access token and `VITE_API_BASE_URL=https://YOUR-SERVICE.onrender.com/api`
   (or `/api` through the local proxy). Never expose database/service-role secrets in VITE values.

## Verification and remaining decisions

From backend with JDK 21:

```sh
./mvnw verify
./mvnw -Ppostgres-it verify
```

The latter requires Docker and runs disposable PostgreSQL 17 containers, actual V001–V003, mocked
Supabase Auth, and full Spring MVC/service/JPA integration. No production credentials are needed.
Coverage includes preexisting-row preservation, SQL/JPA UUID and timestamp defaults, FK/length/
nullability/RLS/privileges, publish/reload/list isolation, role restrictions, invalid input with
unchanged state, static-data pagination, and a forced failed insert with rollback. Ordinary tests
exercise controllers with real services and mocked persistence, plus direct service validation.
Existing account/auth/profile/organization tests remain regression coverage. CI currently runs
ordinary backend verify; PostgreSQL integration tests require the explicit Maven profile.

D02 is resolved only for this ticket: ORGANIZATION managers create events and are recorded as
creator, superseding the DDD's ambiguous admin-creator wording for this operation. ADMIN publishing
and organization verification gating remain unapproved. D06 image storage/access/expiry policy
remains open. Future-only publication/upcoming-only listing, registration configuration/defaults,
HTTP URL compatibility, pagination, and end-after-start are explicit bounded API choices approved
for this update. Historical event reads and actual registration behavior remain separate work. The referenced docs/development-decisions.md
register is absent in this checkout; no contents or broader decisions are invented for it.

Not implemented: RSVP/registration, following, event edit/delete/cancellation, hours, recommendations,
organization verification/onboarding/signup, image upload, individual event detail, dashboard/UI,
GET /organizations/me/opportunities, or retry/idempotency guarantees. Repeating a successful POST
creates another event; clients should avoid automatic retries after uncertain outcomes. General
request rate/body limits and stronger pagination under concurrent inserts remain future work.

## PR #19 update coverage and file inventory

The extension updates Event, EventRepository, OpportunityFailure, OpportunityResponse,
OpportunityService, PublishOpportunityRequest, and OpportunityExceptionHandler in the existing
opportunity package; adds RegistrationType and OpportunityConfiguration (UTC Clock bean); modifies
OpportunityControllerTest, OpportunityServiceTest, and OpportunityPersistenceIT; updates only
V003, this guide, root README, docs/api.md, docs/data-model.md, and docs/security.md. Existing
controllers, request filter, organization/Auth/account code, V001/V002, frontend, CI, and build
configuration remain unchanged. No separate implementation or V004 is added.

Tests now use a fixed injected clock, including direct service calls and MVC/PostgreSQL flows.
They cover rejection of past/equal/sub-microsecond starts without writes, the next representable
future microsecond, UTC offset normalization, end-after-start, SQL filtering before pagination,
aging events remaining stored, stable ordering and organization isolation. Registration coverage
includes omission defaults, explicit INTERNAL/EXTERNAL, invalid/null types, URL combinations,
malformed/unsafe/credential-bearing URLs, ports, Unicode/controls, URLs longer than VARCHAR(255),
SQL defaults/CHECKs/nullability, response fields, and unchanged role/ownership authorization.
The central PostgreSQL acceptance flow publishes INTERNAL and EXTERNAL as A, reloads their stored
registration configuration, lists both for A and neither for B, then rejects past and STANDARD
publication without changing any existing rows. It creates no event registration records.

## Original PR change inventory

Added under `src/main/java/edu/gatech/sip6321/opportunity/`: `Event.java`,
`EventRepository.java`, `PublishOpportunityRequest.java`, `OpportunityResponse.java`,
`OpportunityPage.java`, `OpportunityService.java`, `OpportunityController.java`,
`OpportunityFailure.java`, `OpportunityExceptionHandler.java`, and `OpportunityRequestFilter.java`.
The route-scoped filter supplies consistent 405/415 JSON errors before MVC selects a controller.
Added matching `OpportunityControllerTest.java`, `OpportunityServiceTest.java`, and
`OpportunityPersistenceIT.java` under `src/test/java/edu/gatech/sip6321/opportunity/`.
Also added this guide and V003; updated root README and docs/api, data-model, architecture, security.
Existing Auth/account/organization Java, V001/V002, frontend code, build files, and CI are unchanged.

## Historical verification record — initial PR, before the future-only/registration update

Final verification used Temurin JDK 21.0.5, Node 24.13.0, and disposable PostgreSQL 17 containers.

| Command | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw verify` | 349 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 349 passed | 35 passed | BUILD SUCCESS |

All final runs had zero failures, errors, or skips. Spotless and executable JAR packaging passed.
The initial PR tests contributed 51 ordinary tests and 8 PostgreSQL tests. The central acceptance test
published through MVC, verified the SQL row and a fresh JPA reload, listed it as STANDARD and both
organization managers, proved another organization's list excluded it, and rejected STANDARD
publication without changing database rows. The new migration preserved existing account and
organization rows; isolated tests confirmed defaults, constraints, RLS/browser privileges, stable
pagination, and rollback after a forced insert failure. Existing signup/login/profile/account/
organization regressions passed.

Frontend `npm ci`, typecheck, lint, format:check, all 35 tests, and production build passed without
frontend source changes. This covers the existing mocked frontend authentication/session flows;
no live browser/Supabase/Render smoke test was performed.

The initial sandboxed Mockito run could not attach its agent; final backend tests ran with local
test permissions. Docker Desktop was started with authorization. Early verification exposed a
pre-controller 405 response-format gap, fixed with the route-scoped filter and regression tests.
A build overlapping the final timestamp-boundary edit caught unformatted Java; after formatting,
both final commands above passed against the completed source.

Tracked/staged whitespace checks, whitespace review of all 15 new files, new local documentation
links, and changed-file credential review passed. Historical references to the absent decision
register/readiness report remain unresolved. No live Supabase migration or production data access,
Render/Vercel deployment, credential changes, account provisioning, commit, push, or merge occurred.

## Verification record — PR #19 future-only/registration update

Verified on November 12, 2026 with Temurin JDK 21.0.5 and disposable PostgreSQL 17 containers:

| Command from backend/ | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw -Dtest=OpportunityControllerTest,OpportunityServiceTest test` | 101 passed | Not requested | BUILD SUCCESS |
| `./mvnw verify` | 399 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 399 passed | 39 passed | BUILD SUCCESS |

All final runs had zero failures, errors, or skips; formatting and executable JAR packaging passed.
The opportunity package contributes 92 controller tests, 9 service tests, and 12 PostgreSQL tests.
Existing account/Auth/profile/organization regressions passed. The central acceptance criteria
remain satisfied for both future INTERNAL and future EXTERNAL opportunities, including stored
creator/organization identity, registration fields returned at creation and subsequent listing,
isolation from another organization, and rejection without writes for past/unauthorized submissions.

An initial targeted run executed 97 tests with 29 failures: registration DTO errors were wrapped
by Jackson and mapped to generic INVALID_REQUEST, causing the more specific error-code assertions
to fail; failed-request test output also affected the log-sanitization assertion. The handler now
unwraps only fixed application OpportunityFailure messages, without exposing parser/URL values.
After the fix and additional Unicode/direct-call coverage, all final commands above passed.
Backend tests used local test permissions for Mockito, HTTP test servers, and Docker. No remaining
environmental limitation prevented the requested checks. Frontend code did not change, so frontend
verification was not rerun for this update; its prior results above are historical only.

The V003 SQL embedded in this guide exactly matches the migration file. Changed/new-file
whitespace and credential-pattern review, tracked/staged diff checks, and new local documentation
links passed. Historical missing decision-register/readiness-report references remain unresolved.
V001/V002, event PK/FKs/RLS/revocations/index, existing authentication/organization services,
frontend, CI, and build configuration are unchanged. No live Supabase access/migration, production
credentials/data changes, Render/Vercel deployment, commit, push, merge, or remote PR edits occurred.
