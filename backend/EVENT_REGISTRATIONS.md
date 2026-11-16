# Internal event registration backend

This approved backend extension adds internal registration within the existing Opportunity and
RSVP service area. It reuses Supabase Auth, ProfileService, persisted application accounts,
OrganizationAuthorization, JPA and PostgreSQL. No frontend changes or new configuration variables.
V001–V004 are already applied and immutable. The historical decision register referenced as D02/D11
is absent; this task explicitly resolves volunteer eligibility, cancellation and attendee access
for this scope. Broader membership, attendance and history features remain outside it.

## Authentication and rules

All endpoints require exactly one `Authorization: Bearer ACCESS_TOKEN` header. The existing
Supabase authenticated-user lookup verifies the token; ProfileService resolves `public.users`
by the verified UUID. STANDARD accounts and organization OWNER/ADMIN accounts all volunteer.
Global ADMIN/ORGANIZATION roles do not grant organization permissions. No client identity,
organization or role is used to select a volunteer. POST and DELETE reject nonempty bodies.
GETs accept no body; query parameters only control pagination, never identity.

Registration requires an existing INTERNAL event with persisted `event_start` strictly after the
injected server Clock, compared without rounding the clock to PostgreSQL microseconds. Eligibility
is checked during the service operation, before the write; it is not a promise that the response
or transaction commits before start if a request stalls. No capacity, approval or waitlist.
Cancellation physically removes only the caller's row before start. The event stays intact.
Re-registration creates a new ID/timestamp. Past registrations remain readable.

The database supplies UUID and `registered_at` (transaction start via `NOW()`, microsecond
precision). `UNIQUE(event_id,user_id)` and an atomic targeted `INSERT ... ON CONFLICT ... DO NOTHING`
prevent duplicates across concurrent requests/instances. Exactly one competing insert succeeds;
the other returns 409 after the winner commits. Unexpected integrity errors remain 503, never
misreported as duplicates. Cancellation uses a single user/event-scoped DELETE; concurrent deletes
return one 204 and one 404. Concurrent registration/cancellation follows PostgreSQL statement
visibility and locking: a cancellation that sees no committed registration may return 404 while
an overlapping registration succeeds. Clients should refresh status after uncertain outcomes.

## Endpoints

All JSON success/error responses and the empty 204 use `Cache-Control: no-store`.
All paths below are relative to the backend origin. UUIDs are strings; times are ISO-8601 UTC.
No request body or Content-Type header is needed for these operations.

| Method | Path | Success | Authorization |
| --- | --- | --- | --- |
| POST | `/api/opportunities/{eventId}/registrations/me` | 201, registration | Any registered account, upcoming INTERNAL event |
| GET | `/api/opportunities/{eventId}/registrations/me` | 200, own status | Any registered account |
| DELETE | `/api/opportunities/{eventId}/registrations/me` | 204, empty body | Only caller's registration, upcoming event |
| GET | `/api/users/me/registrations?page=0&size=20` | 200, personal page | Only caller's registrations |
| GET | `/api/organizations/me/opportunities/{eventId}/registrations?page=0&size=20` | 200, attendee page | Persisted OWNER/ADMIN of event's organization |

POST response example:

```json
{"id":"REGISTRATION_UUID","eventId":"EVENT_UUID","userId":"VERIFIED_ACCOUNT_UUID","registeredAt":"2026-11-16T18:00:00Z"}
```

GET status examples (both 200 for an existing event):

```json
{"eventId":"EVENT_UUID","registered":true,"registration":{"id":"REGISTRATION_UUID","registeredAt":"2026-11-16T18:00:00Z"}}
```

```json
{"eventId":"EVENT_UUID","registered":false,"registration":null}
```

Status works for past and EXTERNAL events too (normally false for EXTERNAL); it does not create
anything. Missing events return 404 on status, registration, cancellation and attendee listing.
An event with no caller registration returns false on status and 404 on cancellation. Cancellation
checks event existence/start first, so any started event returns 400 even if the row is absent.
POST checks INTERNAL and start before checking duplication. A manager requesting another
organization's existing event gets 403, without reading its attendees. A nonmanager gets 403
before event lookup. An empty attendee list is 200, including for an EXTERNAL event.

Personal list example:

```json
{"items":[{"id":"REGISTRATION_UUID","eventId":"EVENT_UUID","title":"Park cleanup","organizationId":"ORGANIZATION_UUID","eventStart":"2027-01-16T14:00:00Z","eventEnd":"2027-01-16T17:00:00Z","location":"Atlanta","registeredAt":"2026-11-16T18:00:00Z"}],"page":0,"size":20,"hasNext":false}
```

Attendee list example:

```json
{"items":[{"id":"REGISTRATION_UUID","userId":"VOLUNTEER_UUID","firstName":"Alex","lastName":"Volunteer","registeredAt":"2026-11-16T18:00:00Z"}],"page":0,"size":20,"hasNext":false}
```

Both pages use zero-based `page` (0–10000, default 0), `size` (1–100, default 20), and
`registered_at ASC, id ASC` ordering. No total count; `hasNext` comes from a SQL Slice. Empty/out-of-range
pages have `items:[]` and `hasNext:false`. Ties are deterministic on an unchanged dataset.
Concurrent insert/delete can shift offset pages; no snapshot across requests is promised.
Personal lists include historical events. Attendee DTOs contain no email or other private profile fields.

## Errors

Fixed response shape: `{"code":"ALREADY_REGISTERED","message":"You are already registered for this event."}`.
Never display raw exception details. A 401 also includes `WWW-Authenticate: Bearer`.

| HTTP | Code | Applies to / meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Malformed event UUID, invalid pagination, nonempty mutation body |
| 400 | INTERNAL_REGISTRATION_REQUIRED | POST on EXTERNAL event |
| 400 | EVENT_ALREADY_STARTED | POST/DELETE at or after start |
| 401 | UNAUTHORIZED | Missing, malformed, duplicate, invalid or expired token; all endpoints |
| 403 | FORBIDDEN | Attendee request without matching persisted organization authority |
| 404 | EVENT_NOT_FOUND | Event lookup failed on any event-scoped endpoint |
| 404 | REGISTRATION_NOT_FOUND | DELETE finds no own row (including concurrent repeated delete) |
| 409 | ALREADY_REGISTERED | POST finds existing unique pair, including concurrent duplicate |
| 405 | METHOD_NOT_ALLOWED | Unsupported method on a registration route |
| 415 | UNSUPPORTED_MEDIA_TYPE | Unsupported request content type |
| 429 | AUTH_RATE_LIMITED | Existing Auth provider rate limit |
| 502 | AUTH_RESPONSE_INVALID | Invalid upstream Auth response |
| 503 | AUTH_UNAVAILABLE | Auth provider unavailable |
| 503 | ACCOUNT_PROVISIONING_REQUIRED | Verified Auth identity has no persisted application account |
| 503 | ACCOUNT_UNAVAILABLE | Account database lookup failed |
| 503 | REGISTRATION_UNAVAILABLE | Registration database/transaction failure |
| 500 | INTERNAL_ERROR | Sanitized unexpected failure |

All endpoints can return authentication, account, database or unexpected failures. Event-specific
errors apply as described above; list endpoints additionally validate pagination. IDs in paths must
be valid UUIDs. Request parsing can fail before authentication, consistent with existing controllers.

## curl and frontend integration

Use placeholders in a private API client; do not print real sessions in demo recordings.

```sh
BASE='http://127.0.0.1:8080'
EVENT_ID='EVENT_UUID'
VOLUNTEER_TOKEN='VOLUNTEER_ACCESS_TOKEN'
OWNER_TOKEN='OWNER_ACCESS_TOKEN'
curl -i -X POST "$BASE/api/opportunities/$EVENT_ID/registrations/me" -H "Authorization: Bearer $VOLUNTEER_TOKEN"
curl -i "$BASE/api/opportunities/$EVENT_ID/registrations/me" -H "Authorization: Bearer $VOLUNTEER_TOKEN"
curl -i "$BASE/api/users/me/registrations?page=0&size=20" -H "Authorization: Bearer $VOLUNTEER_TOKEN"
curl -i "$BASE/api/organizations/me/opportunities/$EVENT_ID/registrations?page=0&size=20" -H "Authorization: Bearer $OWNER_TOKEN"
curl -i -X DELETE "$BASE/api/opportunities/$EVENT_ID/registrations/me" -H "Authorization: Bearer $VOLUNTEER_TOKEN"
```

For INTERNAL cards, fetch status and show Register or Registered from `registered`. After POST/DELETE,
refresh status and any affected lists. Disable changes when start has passed; server checks remain
authoritative. Handle 409 by refreshing status; after a network timeout, check status before retrying.
Never send userId/organizationId/role in a mutation. EXTERNAL cards use the existing `registrationUrl`
with safe external navigation, without forwarding tokens, without an internal registration call,
and without interpreting a link click as registration. This ticket includes no React implementation.

## Complete V005 migration

The schema is reviewed against the explicit task: a surrogate UUID PK replaces the historical DDD
composite PK while preserving unique event/user pairs. Only four requested columns are added.
NO ACTION FKs preserve users/events; both query directions have ordered indexes. RLS has no browser
policies, and PUBLIC/anon/authenticated have no direct table privileges.

```sql
-- Apply manually once after V001–V004. Never rerun or edit applied migrations.
BEGIN;

CREATE TABLE public.event_registrations (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT event_registrations_pkey PRIMARY KEY (id),
    CONSTRAINT event_registrations_event_user_key UNIQUE (event_id, user_id),
    CONSTRAINT event_registrations_event_fkey FOREIGN KEY (event_id)
        REFERENCES public.events (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT event_registrations_user_fkey FOREIGN KEY (user_id)
        REFERENCES public.users (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- Support attendee and personal registration pagination with a deterministic tie-breaker.
CREATE INDEX event_registrations_event_time_id_idx
    ON public.event_registrations (event_id, registered_at, id);
CREATE INDEX event_registrations_user_time_id_idx
    ON public.event_registrations (user_id, registered_at, id);
ALTER TABLE public.event_registrations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.event_registrations FROM PUBLIC, anon, authenticated;
-- No browser policies. Review backend JDBC grants and effective RLS access separately.
COMMIT;
```

## Manual Supabase application (operator only)

1. Confirm the target project and reviewed source revision. Back up according to the team's process.
   V001–V004 are confirmed already applied: **do not edit or rerun them**. Run these read-only prechecks:

   ```sql
   SELECT to_regclass('public.users'), to_regclass('public.organizations'),
          to_regclass('public.events'), to_regclass('public.organization_management_audit'),
          to_regclass('public.event_registrations');
   SELECT column_name, data_type FROM information_schema.columns
   WHERE table_schema='public' AND table_name='events'
   AND column_name IN ('registration_type','event_start');
   ```

   Expect the first four tables present and event_registrations absent. If not, stop and review drift.
2. Open Supabase SQL Editor → New query as the database owner. Paste the **entire**
   [V005 file](db/migrations/V005__create_event_registrations.sql), including BEGIN/COMMIT, and execute
   once. No placeholders or legacy mappings are needed. If it fails, roll back the failed session and
   inspect the cause; retry only after confirming V005 did not commit. Do not rerun a successful V005.
3. Run these read-only checks and record operator, source revision, date and migration version:

   ```sql
   SELECT column_name, data_type, is_nullable, column_default
   FROM information_schema.columns
   WHERE table_schema='public' AND table_name='event_registrations' ORDER BY ordinal_position;
   SELECT conname, convalidated, pg_get_constraintdef(oid)
   FROM pg_constraint WHERE conrelid='public.event_registrations'::regclass ORDER BY conname;
   SELECT indexname,indexdef FROM pg_indexes
   WHERE schemaname='public' AND tablename='event_registrations';
   SELECT relrowsecurity FROM pg_class WHERE oid='public.event_registrations'::regclass;
   SELECT * FROM pg_policies WHERE schemaname='public' AND tablename='event_registrations';
   SELECT grantee, privilege_type FROM information_schema.table_privileges
   WHERE table_schema='public' AND table_name='event_registrations';
   SELECT r.role_name,p.privilege,
          has_table_privilege(r.role_name,'public.event_registrations',p.privilege) AS allowed
   FROM (VALUES ('anon'),('authenticated')) r(role_name)
   CROSS JOIN (VALUES ('SELECT'),('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE'),('REFERENCES'),('TRIGGER')) p(privilege);
   ```

   Expect UUID PK/default, NOT NULL four fields, NOW timestamp default, unique pair, two validated
   NO ACTION FKs, two ordered indexes plus PK/unique indexes, RLS true, no policies, no PUBLIC/browser
   grants and all browser effective privileges false.
4. Confirm the effective existing JDBC role has public schema USAGE, SELECT on users/events,
   SELECT/INSERT/DELETE on event_registrations, and effective backend-only RLS access. Example checks
   (replace the role **name**, never a credential):

   ```sql
   SELECT has_schema_privilege('YOUR_BACKEND_ROLE','public','USAGE');
   SELECT has_table_privilege('YOUR_BACKEND_ROLE','public.event_registrations','SELECT') AS can_read,
          has_table_privilege('YOUR_BACKEND_ROLE','public.event_registrations','INSERT') AS can_insert,
          has_table_privilege('YOUR_BACKEND_ROLE','public.event_registrations','DELETE') AS can_delete;
   SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname='YOUR_BACKEND_ROLE';
   SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='event_registrations';
   ```

   Table owners/superusers/BYPASSRLS roles normally bypass RLS. A restricted backend role needs
   separately reviewed backend-only grants/policies through the existing team process. Do not grant
   browser roles access. `has_table_privilege` alone does not establish effective RLS access. No UUID
   sequence grants needed. Validate through the API in an authorized development project.

No live database action has been performed by the coding agent.

## Render and verification

Apply V005 and verify effective JDBC permissions before deploying the reviewed backend. Use the
existing Render profile, `SIP_AUTH_ENABLED=true`, Supabase URL/publishable key, JDBC credentials/TLS,
PORT and exact CORS origin settings. No environment additions, startup migration or automatic DDL.
Keep SQL/bind, request-body and Authorization logging disabled. Do not deploy code that needs V005
before the migration. An older backend can coexist with the additive table; rollback code need not
remove registration data. Vercel and frontend require no deployment for this task.

From backend with JDK 21:

```sh
./mvnw verify
./mvnw -Ppostgres-it verify
```

PostgreSQL tests require a running Docker daemon. They apply V001–V005 only to disposable PostgreSQL
17 containers with fake Auth schema/roles and mocked Supabase token verification. The ordinary build
uses no live database. Unit/controller tests cover service rules, all roles, authentication, errors,
pagination and sanitized failures. Integration tests cover full register/status/list/attendees/cancel/
re-register flows, independent-request isolation, concurrency, SQL defaults/precision/keys/indexes,
NO ACTION FKs, RLS/browser restrictions, preexisting-data preservation and failed-write rollback.
See [the runnable demo](../docs/demo-internal-registration-backend.md).

Limits: no capacity, waitlist, approval, attendance, hours, notifications, event editing/deletion,
external-provider registration tracking or general rate limiter. No live Supabase/Render smoke test
is implied by container success. Offset pagination and eligibility timing limits are described above.

## File inventory

Added `db/migrations/V005__create_event_registrations.sql`, this guide, and
`../docs/demo-internal-registration-backend.md`. New production classes under
`src/main/java/edu/gatech/sip6321/registration/`:

- [`AttendeeResponse.java`](src/main/java/edu/gatech/sip6321/registration/AttendeeResponse.java)
- [`EventRegistration.java`](src/main/java/edu/gatech/sip6321/registration/EventRegistration.java)
- [`EventRegistrationController.java`](src/main/java/edu/gatech/sip6321/registration/EventRegistrationController.java)
- [`EventRegistrationRepository.java`](src/main/java/edu/gatech/sip6321/registration/EventRegistrationRepository.java)
- [`EventRegistrationResponse.java`](src/main/java/edu/gatech/sip6321/registration/EventRegistrationResponse.java)
- [`EventRegistrationService.java`](src/main/java/edu/gatech/sip6321/registration/EventRegistrationService.java)
- [`MyRegistrationResponse.java`](src/main/java/edu/gatech/sip6321/registration/MyRegistrationResponse.java)
- [`RegistrationExceptionHandler.java`](src/main/java/edu/gatech/sip6321/registration/RegistrationExceptionHandler.java)
- [`RegistrationFailure.java`](src/main/java/edu/gatech/sip6321/registration/RegistrationFailure.java)
- [`RegistrationPage.java`](src/main/java/edu/gatech/sip6321/registration/RegistrationPage.java)
- [`RegistrationRequestFilter.java`](src/main/java/edu/gatech/sip6321/registration/RegistrationRequestFilter.java)
- [`RegistrationStatusResponse.java`](src/main/java/edu/gatech/sip6321/registration/RegistrationStatusResponse.java)

New tests under `src/test/java/edu/gatech/sip6321/registration/`:

- [`EventRegistrationControllerTest.java`](src/test/java/edu/gatech/sip6321/registration/EventRegistrationControllerTest.java)
- [`EventRegistrationPersistenceIT.java`](src/test/java/edu/gatech/sip6321/registration/EventRegistrationPersistenceIT.java)
- [`EventRegistrationServiceTest.java`](src/test/java/edu/gatech/sip6321/registration/EventRegistrationServiceTest.java)

Modified Event.java only to make its existing DTO mapper available across backend packages;
updated backend/OPPORTUNITIES.md, README.md, docs/api.md, docs/data-model.md and docs/security.md.
No existing migration, authentication, organization authorization, frontend, build or CI files changed.

## Verification record — November 16, 2026

Verified on feature/internal-event-registration, based on freshly fetched main
525dff76fbac2864d8386571fbdd32ac408af790 (includes merged organization-management PR #24),
using Temurin JDK 21.0.5 and actual disposable PostgreSQL 17 Docker containers.

| Command from backend | Ordinary tests | PostgreSQL integration tests | Result |
| --- | --- | --- | --- |
| `./mvnw -Dtest=EventRegistrationServiceTest,EventRegistrationControllerTest test` | 28 | Not requested | Passed |
| `./mvnw verify` | 476 | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 476 | 60 | BUILD SUCCESS |

All final runs had zero failures, errors or skipped tests. Registration contributes 11 service,
17 controller and 10 PostgreSQL tests. Existing authentication/account/organization/opportunity
regressions passed. Spotless formatting checks and executable JAR packaging passed. The full
PostgreSQL profile applies the real migration scripts to isolated databases, not Supabase.

The first sandboxed Mockito run could not attach its agent; final tests ran with authorized local
test permissions. Docker Desktop was initially stopped and was started with authorization. The
first new PostgreSQL run found a test-fixture connection retaining SET ROLE after a denied browser
query; the fixture now RESET ROLEs in finally before returning the connection to Hikari. The targeted
rerun and full final profile passed after that correction. No tests were weakened or skipped.

Tracked/staged diff checks, untracked-file whitespace review, changed-file credential-pattern
review, new documentation link targets and embedded V005 SQL equality checks passed. Preexisting
links to absent docs/development-decisions.md and docs/readiness-report.md remain historical gaps;
no missing register contents were invented. Frontend source was untouched and frontend checks were
not rerun for this backend-only task. V001–V004 were unchanged.

No live Supabase access/migration, Render/Vercel deployment, commit, push or merge occurred.
Live Auth verification and deployed JDBC/RLS permissions still require the authorized operator's
smoke test; container tests mock Supabase token verification. Offset pagination may shift with
concurrent writes; event eligibility is checked before the mutation as documented above.
