# Organization creation and management

These approved November 12, 2026 product requirements supersede the former global
`UserRole.ORGANIZATION` authorization model for organization profiles and opportunity publishing.
They resolve the organization-management portion of D02 referenced by older specifications.
The historical `docs/development-decisions.md` register is absent from this checkout; its other
contents and decisions have not been reconstructed. The four layers and five service areas remain:
organization management belongs to Account + Profile and shares authorization with Opportunity.

## Account and permission model

Everyone signs up through the existing volunteer signup flow: global `STANDARD`, null
`organizationId`, null `organizationRole`. Creating or joining an organization never changes
email, password, global role, names, bio, avatar, creation time, or volunteer permissions.
`STANDARD`, `ORGANIZATION`, and global `ADMIN` remain compatible persisted values; none grants
organization authority. No public API accepts arbitrary role, identity, or verification updates.

A user manages at most one organization using `users.organization_id` and the new separate
`organization_role` (`OWNER`, `ADMIN`, or null). Following, volunteer membership, event registration,
and verification do not grant management rights. An organization has exactly one active OWNER
through the creation/removal workflows; OWNER and ADMIN retain normal volunteer capabilities.

| Operation | Unassociated volunteer | OWNER | ADMIN | Global ADMIN without organization role |
| --- | --- | --- | --- | --- |
| Create organization | Yes | No (409) | No (409) | Yes if unassociated |
| Read organization by ID / upcoming opportunities | Yes | Yes | Yes | Yes |
| Read own organization / management team | No | Yes | Yes | No |
| Edit associated organization profile | No | Yes | Yes | No |
| Publish associated organization's opportunities | No | Yes | Yes | No |
| Add/remove ADMIN | No | Yes | No | No |
| Transfer/remove OWNER / delete organization | No | No endpoint | No endpoint | No endpoint |

An OWNER cannot remove themselves. Cross-organization profile writes are 403 before target lookup.
An unrelated, nonexistent, or non-ADMIN removal target is always 404. Admin removal never deletes
the account, Auth identity, events, or volunteer data. Subsequent requests re-read persisted roles,
so removal revokes authorization immediately after commit. Already authorized in-flight work may
complete; no token reissue or logout is needed.

## REST contracts and complete HTTP examples

All routes require an existing registered application account and one verified Supabase Bearer
access token. Existing `SIP_AUTH_ENABLED=true`, database-capable profile, TLS, and exact-origin CORS
settings apply. Every result uses `Cache-Control: no-store`. Examples use symbolic tokens and UUIDs;
substitute real development values privately. No production requests were made.

### Create organization

```http
POST /api/organizations HTTP/1.1
Authorization: Bearer SARAH_ACCESS_TOKEN
Content-Type: application/json
Accept: application/json

{"name":"Habitat Community Volunteers","description":"Connecting Atlanta volunteers.","website":"https://example.org"}
```

```http
HTTP/1.1 201 Created
Location: /api/organizations/ORGANIZATION_UUID
Cache-Control: no-store
Content-Type: application/json

{"organization":{"id":"ORGANIZATION_UUID","name":"Habitat Community Volunteers","description":"Connecting Atlanta volunteers.","website":"https://example.org","isVerified":false,"createdAt":"2026-11-12T19:00:00Z"},"ownerId":"SARAH_UUID","organizationRole":"OWNER"}
```

`name` is required/nonblank, at most 255 Unicode code points. Optional `description` and `website`
may be omitted or null. Website follows the existing organization PATCH rules: absolute HTTP(S),
valid host, no credentials, maximum 255 code points. Existing Unicode/text validation is reused.
Unknown/protected/non-string fields fail with 400, including `ownerId`, `organizationId`, `role`,
`organizationRole`, `id`, `createdAt`, and `isVerified`. Equal names do not merge organizations.

### Add an existing registered ADMIN

```http
POST /api/organizations/me/admins HTTP/1.1
Authorization: Bearer SARAH_ACCESS_TOKEN
Content-Type: application/json
Accept: application/json

{"email":"mike@example.com"}
```

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
```

Only OWNER may perform an exact application-account email lookup. Email is required, formatted,
control-free, at most 255 Java characters; no other field is accepted. Existing account email case
and whitespace semantics are preserved: no lowercasing or guessed account matching. The target must
already have an application account with null organization association/role. This never calls Auth
signup, modifies Auth credentials, requires acceptance, or changes the global role. A missing,
already-associated, self, or otherwise unavailable target receives the same 409 response below.
No email directory or personal data is returned.

### List management team

```http
GET /api/organizations/me/admins HTTP/1.1
Authorization: Bearer MIKE_ACCESS_TOKEN
Accept: application/json
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Content-Type: application/json

[{"id":"SARAH_UUID","firstName":"Sarah","lastName":"Volunteer","organizationRole":"OWNER"},{"id":"MIKE_UUID","firstName":"Mike","lastName":"Volunteer","organizationRole":"ADMIN"}]
```

Both roles may view the associated organization only. Includes OWNER and ADMINs, ordered by UUID;
email, global role, bio, avatar, Auth/session data are excluded. This MVP list is not paginated.

### Remove an ADMIN

```http
DELETE /api/organizations/me/admins/MIKE_UUID HTTP/1.1
Authorization: Bearer SARAH_ACCESS_TOKEN
Accept: application/json
```

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
```

The target must be an ADMIN of the caller's organization. Both association fields clear atomically.
Self-removal is 403, other unavailable targets are 404 without unrelated membership information.

### Profile and existing organization/opportunity APIs

```http
GET /api/users/me HTTP/1.1
Authorization: Bearer SARAH_ACCESS_TOKEN
Accept: application/json
```

```json
{
  "id": "SARAH_UUID",
  "email": "sarah@example.com",
  "role": "STANDARD",
  "firstName": "Sarah",
  "lastName": "Volunteer",
  "bio": null,
  "avatarUrl": null,
  "organizationId": "ORGANIZATION_UUID",
  "organizationRole": "OWNER",
  "createdAt": "2026-11-12T18:00:00Z"
}
```

Unassociated profiles include both association fields as JSON null. All nine former profile fields
are preserved; `organizationRole` is the tenth. Signup/login's smaller user/session contracts are
unchanged. Clients should refresh `/api/users/me` after membership changes and treat UI permissions
as advisory. Backend authorization always uses the verified identity and current database account.

```http
PATCH /api/organizations/ORGANIZATION_UUID HTTP/1.1
Authorization: Bearer MIKE_ACCESS_TOKEN
Content-Type: application/json
Accept: application/json

{"description":"Help Atlanta neighbors.","website":null}
```

Returns the existing six-field organization response, HTTP 200; protected fields remain rejected.
`GET /api/organizations/me` now accepts both OWNER and ADMIN. Authenticated reads by ID remain open
to all existing application accounts. Verification is separate and remains uneditable.

```http
POST /api/organizations/me/opportunities HTTP/1.1
Authorization: Bearer MIKE_ACCESS_TOKEN
Content-Type: application/json
Accept: application/json

{"title":"Build homes","description":"Help neighbors.","eventStart":"2099-02-01T12:00:00Z","eventEnd":"2099-02-01T14:00:00Z","location":"Atlanta","registrationType":"EXTERNAL","registrationUrl":"https://example.org/signup"}
```

Returns HTTP 201 with the existing opportunity contract: id, organizationId, creatorId, title,
description, imageUrl, eventStart, eventEnd, location, createdAt, registrationType, registrationUrl.
The backend derives organization and creator IDs. Both management roles may publish regardless of
global role; no cross-organization target is accepted. Future-start/end-order validation,
INTERNAL omission default, EXTERNAL URL rules, upcoming-only SQL filtering, pagination and stable
ordering remain unchanged. See [opportunity contracts](OPPORTUNITIES.md). Event editing is excluded.

### Errors

All bodies have fixed safe `code` and `message` strings, for example:

```json
{"code":"MEMBERSHIP_CONFLICT","message":"Organization membership could not be assigned."}
```

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Invalid JSON/types/fields/UUID/validation |
| 401 | UNAUTHORIZED | Missing, malformed, expired, or rejected token; WWW-Authenticate: Bearer |
| 403 | FORBIDDEN | Missing management/OWNER authority, cross-organization write, or OWNER self-removal |
| 404 | ORGANIZATION_NOT_FOUND | Associated/requested organization does not exist |
| 404 | ADMIN_NOT_FOUND | Removal target is not an ADMIN of this organization |
| 409 | MEMBERSHIP_CONFLICT | Existing membership, lost concurrent claim, missing/unavailable add target |
| 429 | MANAGEMENT_RATE_LIMITED | OWNER email-lookup throttle exhausted |
| 503 | ORGANIZATION_UNAVAILABLE | Organization database/transaction failure |
| 500 | INTERNAL_ERROR | Sanitized unexpected failure |

Existing Auth/provider/provisioning errors retain their original conventions in [PROFILE.md](PROFILE.md).
Existing organization/opportunity 405/415 handling remains; new controller methods require JSON
for POST and supported HTTP methods. No raw parser, database exception, email or token is returned.

## Security, concurrency, audit and abuse controls

`OrganizationAuthorization` reuses `ProfileService` and Supabase authenticated user verification,
checks persisted organization-specific roles, provides manager/scoped-manager/OWNER methods, and
is reused in all three service areas. Controllers never authorize from frontend state, unsigned
claims, supplied identity, names, following, registration, or global roles.

Creation inserts the organization, conditionally updates the verified user's association only
when both association fields are null, and inserts an audit row in one PostgreSQL transaction.
A conditional-update count other than one raises 409 and rolls back the new organization.
PostgreSQL row locks recheck the predicate after a concurrent update commits, so only one claim
succeeds even across backend instances. Addition uses the same conditional claim with ADMIN.
Removal uses a conditional update on target ID, actor's persisted organization, and ADMIN role.
Both fields change in a single statement. Failed membership/audit writes roll back the operation.
No isolation override, new framework, or process-local lock is used for data consistency.

Email lookup is OWNER-only, with a bounded per-owner, per-backend-instance limit of 10 attempts
per 60-second window starting at the first attempt, including failed
lookups. The map holds at most 4096 active owners and fails closed when full. `System.nanoTime`
keeps throttling independent of wall-clock changes. Restarting a backend resets its limiter;
multiple replicas multiply the available rate. Distributed limits and operational monitoring are
future work. Authorized OWNERs can still infer eligibility from success vs conflict; the approved
direct-add model cannot eliminate that distinction. No arbitrary volunteer can enumerate accounts.

`organization_management_audit` is a transactional backend-only record with generated UUID,
actor ID, target ID, organization ID, action (OWNER_CREATED / ADMIN_ADDED / ADMIN_REMOVED), and
server timestamp. It contains no email, credentials, request body, or profile snapshots. UUID FKs
use NO ACTION, RLS is enabled, and PUBLIC/anon/authenticated privileges are revoked. No public audit
API is added. Review effective JDBC INSERT permissions/RLS separately; production SQL/bind/body/token
logging must remain disabled. Audit records are retained; retention/deletion policy must be reviewed
before any future account/organization deletion workflow. Existing FKs/RLS/event rows are preserved.

**Notification requirement:** operations must ensure an added user is told their organization name,
new ADMIN role, adding OWNER, time, lack of volunteer-account changes, and recovery contact. This
backend records the successful addition but does not send email, add an outbox, or promise delivery.
Before enabling direct addition in production, assign an operator notification process or implement
an approved notification integration; no external messages were sent by this task.

**Incorrect-addition recovery:** the user contacts the organization's OWNER or team support via the
team's existing channel (no address invented here). OWNER removes them with DELETE above; verify
`/api/users/me` returns null association/role and organization writes now fail. If OWNER is
unavailable, support verifies the requesting account's identity and reviews the audit before a
separately authorized backend/operator removal of that ADMIN, retaining an audit trail. Do not
remove OWNER, delete accounts/Auth identities, change global roles, or silently reassign ownership.
Support escalation procedures/contact and automated notifications remain operational limitations.

## V004 design and legacy reconciliation

The project owner confirms V001, V002, and V003 are already applied to Supabase. They are unchanged,
including historical V003 comments. Never rerun or modify them. The new backend requires V004 first.
No production database was inspected; actual legacy account presence is unknown.

Run the read-only [preflight SQL](db/preflight/organization_management.sql) manually with an
authorized operator role. It lists existing associations, global ORGANIZATION accounts, zero-user
organizations, multiple-user organizations, dangling associations, unassociated legacy
ORGANIZATION accounts, and associated accounts with other global roles. Results contain private
emails and must remain within the review team. The queries do not assume organization_role exists.

Default V004 automatically migrates only a database with no organizations/associations: all
unassociated accounts receive a null role and retain every existing field, including global role.
An unassociated legacy global ORGANIZATION account can remain unassociated and gain no management
permissions. Review it, but do not promote it implicitly.

For every legacy organization, the team must explicitly approve one existing registered OWNER and
classify all its other associated users as ADMIN. Even a single associated ORGANIZATION account is
not automatically promoted: the user has not authorized an inferred owner mapping. For an
organization with zero associated users, explicitly select an unassociated registered OWNER;
otherwise stop deployment and keep all data intact. No accounts or organizations are deleted.

Place the approved UUID mapping in V004's `approved_organization_roles` temporary table INSERT
section before its first DO block. The table is transaction-local and dropped on commit. Each
associated account must be mapped, every organization must have exactly one approved OWNER, all
IDs must exist, and an existing non-null association cannot be moved to a different organization.
Unassociated users can be assigned only via explicit approved mapping. No global role is rewritten.
Review/save the actual approved deployment script as the team's migration record; after application,
it is immutable. This is a manual review gate, not runtime migration configuration.

V004 locks users/organizations against concurrent writes, adds the column, validates the approved
mapping, applies only that mapping, rejects unreconciled/ownerless/multi-owner data, adds role and
association CHECK constraints and a partial unique OWNER index, then adds the scoped audit table.
The association CHECK requires both values null or both present; the role CHECK limits present
values to OWNER/ADMIN. Multiple ADMINs are permitted. A scalar organization_id retains the one-org
rule. Partial uniqueness guarantees at most one OWNER; creation and removal services preserve at
least one for supported workflows. Privileged direct SQL/internal fixture provisioning can still
create ownerless organizations; no claim of a universal database at-least-one constraint is made.

A failed migration must be rolled back in the SQL session before any retry. Nothing is silently
inferred, cleared, deleted, or partially committed. See the exact final SQL below.

## TypeScript integration

The frontend Profile type/validator includes organizationRole. The organization-creation,
organization-profile and opportunity screens subsequently merged into main are preserved. Their
creation adapter unwraps the ownership response, and their edit/publish controls allow the matching
organization's OWNER or ADMIN regardless of global role. Unassociated registered accounts may
create an organization. No new admin-team management UI is added by this merge.

```ts
type OrganizationRole = 'OWNER' | 'ADMIN';
type CreatedOrganization = {
  organization: {
    id: string; name: string; description: string | null; website: string | null;
    isVerified: boolean; createdAt: string;
  };
  ownerId: string;
  organizationRole: 'OWNER';
};
type ManagementMember = {
  id: string; firstName: string; lastName: string; organizationRole: OrganizationRole;
};
async function addAdmin(apiBase: string, accessToken: string, email: string) {
  const response = await fetch(`${apiBase}/organizations/me/admins`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });
  if (!response.ok) throw new Error(`Admin addition failed (${response.status})`);
  // 204 has no JSON body. Refresh the actor's profile/team as needed.
}
```

Use the current Supabase access token. Client checks such as `profile.organizationRole === 'OWNER'`
only control presentation. Keep all database and privileged Auth credentials outside React/VITE_*.
Deploying the strict updated frontend profile validator requires the new backend response first.
Older clients that ignore the additive field remain compatible with the new backend.

## Manual Supabase rollout

1. Review this guide, exact V004, preflight results, existing applied migration records, and explicit
   legacy mapping. Confirm V003 includes the PR #19 registration columns; if live schema differs,
   stop for a separate additive repair review. Never edit/rerun V001–V003 based on stale comments.
2. Verify a recoverable backup and effective backend permissions. Pause organization-management
   writes/legacy provisioning during the migration/backend transition; avoid a mixed-version
   deployment where old code creates associations without organization_role. Standard unassociated
   signup remains compatible, but a short maintenance window is simplest.
3. Run only the reviewed V004 transaction manually in Supabase SQL Editor. If its reconciliation
   gate raises an error, issue ROLLBACK, obtain the missing explicit mapping approval, and review
   the whole script again. Do not weaken constraints or guess OWNERs to force a deployment.
4. Read back the state with authorized operator queries:

   ```sql
   SELECT organization_id, count(*) FILTER (WHERE organization_role='OWNER') AS owners,
          count(*) FILTER (WHERE organization_role='ADMIN') AS admins
   FROM public.users WHERE organization_id IS NOT NULL GROUP BY organization_id;
   SELECT o.id FROM public.organizations o LEFT JOIN public.users u
       ON u.organization_id=o.id AND u.organization_role='OWNER'
   GROUP BY o.id HAVING count(u.id)<>1; -- zero rows
   SELECT id FROM public.users WHERE (organization_id IS NULL) <> (organization_role IS NULL);
   -- zero rows
   SELECT indexdef FROM pg_indexes WHERE indexname='users_one_organization_owner_idx';
   SELECT relname, relrowsecurity FROM pg_class
   WHERE oid IN ('public.users'::regclass,'public.organization_management_audit'::regclass);
   SELECT grantee,privilege_type FROM information_schema.table_privileges
   WHERE table_schema='public' AND table_name='organization_management_audit';
   -- No PUBLIC/anon/authenticated grants; also inspect inherited privileges/policies.
   ```

5. Review backend schema USAGE; users SELECT/UPDATE; organizations SELECT/INSERT/UPDATE; events
   SELECT/INSERT; audit INSERT. Ordinary API requests need no audit SELECT/UPDATE/DELETE.
   Restricted roles also need separately reviewed backend-only RLS policies; table ownership or
   BYPASSRLS behavior must be understood. Do not grant application tables to browser roles or
   disable RLS. No grants or production SQL are executed by this change.
6. Verify original user/global-role/organization/event counts and rows are preserved except the
   explicitly approved association/role mapping. Preserve the applied-script review record.

## Manual Render rollout

1. After V004 succeeds and backend privileges/RLS are reviewed, deploy the reviewed feature revision
   through the team's normal Render process. Do not run migration SQL from app startup.
2. No new environment variables or infrastructure are required. Preserve `SIP_AUTH_ENABLED`,
   database-capable prod/render profile, JDBC TLS credentials, Supabase public Auth configuration,
   PORT and exact CORS origins. Keep `ddl-auto=none` and SQL initialization disabled.
3. Confirm health, then use separately authorized development accounts to exercise Sarah/Mike:
   volunteer signup/login, create organization, OWNER profile, add registered Mike, ADMIN profile,
   both profile edits and publication, forbidden ADMIN membership edits/cross-org writes, removal,
   immediate denial of Mike's subsequent management requests, and intact volunteer login/profile.
   Test INTERNAL and EXTERNAL/future-only/listing regressions. Health alone does not test RLS/grants.
4. Resume management writes only after the new version is active and smoke checks succeed. Complete
   the notification/recovery operational requirement before enabling direct addition for users.
5. No Vercel deployment is performed. If deploying the compatible frontend separately,
   deploy it after the backend. Do not roll back to the old global-role authorization version after
   new STANDARD OWNER/ADMIN accounts exist; forward-fix or use a separately reviewed compatibility
   rollback while keeping V004/data. Do not drop the new column/table or rewrite global roles.

## Verification and limitations

Required commands:

```sh
cd backend
./mvnw verify
./mvnw -Ppostgres-it verify
cd ../frontend
npm ci
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build
```

Verification results are recorded below after execution. All database tests use disposable
PostgreSQL 17 Testcontainers with V001 -> V002 -> V003 -> V004, plus separate disposable legacy
migration fixtures. Auth is mocked or locally stubbed; no live Supabase/Render/Vercel calls.

Not implemented: ownership transfer/deletion, multi-organization management, global admin redesign,
invitations/acceptance, event edit/delete, RSVP, following, hours, provider integrations, management
UI, automated notification delivery, distributed rate limiting, or automatic deployment/migrations.
The existing signup model does not prove email ownership; changing that is outside this ticket.
No live-data compatibility claim is made until an operator reviews preflight results.

## Change inventory

Added V004 and read-only preflight SQL; OrganizationRole; shared OrganizationAuthorization;
OrganizationManagementService/Controller; strict create/add DTOs; ownership/team response DTOs;
bounded ManagementAttemptLimiter; controller/authorization/PostgreSQL management tests and an
isolated migration test helper; this guide. Modified User/UserRepository/ProfileResponse;
organization/opportunity services and exception handlers; existing profile/organization/opportunity/
account regression fixtures; frontend Profile validation/type and test fixture; relevant backend,
API, security, architecture, data-model and README documentation; profile smoke contract check.
V001–V003, existing Auth verification, signup/login implementation, event validation/pagination,
build dependencies, infrastructure, CI and production configuration remain unchanged.

## Exact V004 SQL

```sql
-- Manual, additive migration after the already-applied V001/V002/V003. Never rerun those files.
-- Run db/preflight/organization_management.sql first. No automatic legacy ownership inference.
BEGIN;

-- Block concurrent legacy writes while validating and applying the approved mapping.
LOCK TABLE public.organizations, public.users IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE public.users ADD COLUMN organization_role VARCHAR(20);

-- REVIEW GATE: leave empty for a database without organizations/associations.
-- For legacy data, insert ONLY team-approved UUID mappings here before manual execution.
-- Every associated user and every organization's exactly one OWNER must be accounted for.
-- Unassociated existing users may be explicitly selected for an ownerless legacy organization.
CREATE TEMP TABLE approved_organization_roles (
    user_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    organization_role VARCHAR(20) NOT NULL CHECK (organization_role IN ('OWNER', 'ADMIN'))
) ON COMMIT DROP;
-- INSERT INTO approved_organization_roles VALUES
--     ('APPROVED_USER_UUID', 'EXISTING_ORGANIZATION_UUID', 'OWNER');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM approved_organization_roles m
        LEFT JOIN public.users u ON u.id = m.user_id
        LEFT JOIN public.organizations o ON o.id = m.organization_id
        WHERE u.id IS NULL OR o.id IS NULL
           OR (u.organization_id IS NOT NULL AND u.organization_id <> m.organization_id)
    ) THEN
        RAISE EXCEPTION 'V004: invalid approved mapping; review legacy reconciliation';
    END IF;
END $$;

UPDATE public.users u
SET organization_id = m.organization_id, organization_role = m.organization_role
FROM approved_organization_roles m WHERE u.id = m.user_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.users WHERE organization_id IS NOT NULL AND organization_role IS NULL)
       OR EXISTS (
           SELECT 1 FROM public.organizations o
           LEFT JOIN public.users u ON u.organization_id = o.id AND u.organization_role = 'OWNER'
           GROUP BY o.id HAVING count(u.id) <> 1
       ) THEN
        RAISE EXCEPTION 'V004: explicit OWNER/ADMIN reconciliation required; transaction must be rolled back';
    END IF;
END $$;

ALTER TABLE public.users ADD CONSTRAINT users_organization_role_check
    CHECK (organization_role IS NULL OR organization_role IN ('OWNER', 'ADMIN'));
ALTER TABLE public.users ADD CONSTRAINT users_organization_association_check CHECK (
    (organization_id IS NULL AND organization_role IS NULL)
    OR (organization_id IS NOT NULL AND organization_role IS NOT NULL)
);
CREATE UNIQUE INDEX users_one_organization_owner_idx ON public.users (organization_id)
    WHERE organization_role = 'OWNER';
CREATE INDEX users_organization_id_idx ON public.users (organization_id);

-- Transactional, backend-only audit of successful changes. No email/token/profile snapshots.
CREATE TABLE public.organization_management_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    target_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    organization_id UUID NOT NULL REFERENCES public.organizations(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    action VARCHAR(20) NOT NULL CHECK (action IN ('OWNER_CREATED', 'ADMIN_ADDED', 'ADMIN_REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX organization_management_audit_organization_time_idx
    ON public.organization_management_audit (organization_id, created_at, id);
ALTER TABLE public.organization_management_audit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.organization_management_audit FROM PUBLIC, anon, authenticated;
-- Existing FKs, global roles, event records, RLS and browser revocations are preserved.
-- Review effective backend users UPDATE, organizations INSERT, audit INSERT and RLS access.
COMMIT;
```

## Exact read-only preflight SQL

```sql
-- READ ONLY. Run manually with an authorized operator role; results include private account emails.
BEGIN TRANSACTION READ ONLY;
-- Existing associations (all global roles): each requires an explicit approved mapping in V004.
SELECT u.id, u.email, u.role, u.organization_id, o.name
FROM public.users u LEFT JOIN public.organizations o ON o.id = u.organization_id
WHERE u.organization_id IS NOT NULL ORDER BY u.organization_id, u.id;
-- Global legacy ORGANIZATION accounts, including unassociated accounts.
SELECT id, email, role, organization_id FROM public.users
WHERE role = 'ORGANIZATION' ORDER BY id;
-- Organizations with no associated users: choose a registered OWNER explicitly; never delete them.
SELECT o.id, o.name FROM public.organizations o LEFT JOIN public.users u ON u.organization_id = o.id
GROUP BY o.id, o.name HAVING count(u.id) = 0 ORDER BY o.id;
-- Multiple associations: explicitly select exactly one OWNER and classify the remaining ADMINs.
SELECT o.id, o.name, count(u.id) AS associated_users
FROM public.organizations o JOIN public.users u ON u.organization_id = o.id
GROUP BY o.id, o.name HAVING count(u.id) > 1 ORDER BY o.id;
-- Legacy inconsistencies for review; STANDARD/ADMIN associations must not be silently discarded.
SELECT u.id, u.email, u.role, u.organization_id,
    CASE WHEN u.organization_id IS NOT NULL AND o.id IS NULL THEN 'DANGLING_ORGANIZATION'
         WHEN u.role = 'ORGANIZATION' AND u.organization_id IS NULL THEN 'UNASSOCIATED_LEGACY_ROLE'
         ELSE 'ASSOCIATED_NON_ORGANIZATION_GLOBAL_ROLE' END AS review_reason
FROM public.users u LEFT JOIN public.organizations o ON o.id = u.organization_id
WHERE (u.organization_id IS NOT NULL AND o.id IS NULL)
   OR (u.role = 'ORGANIZATION' AND u.organization_id IS NULL)
   OR (u.role <> 'ORGANIZATION' AND u.organization_id IS NOT NULL)
ORDER BY u.id;
COMMIT;
```

## Exact file inventory

Compared with main after the November 15 compatibility merge: 52 files (36 modified, 16 added).

### Added

- `backend/ORGANIZATION_MANAGEMENT.md`
- `backend/db/migrations/V004__organization_management.sql`
- `backend/db/preflight/organization_management.sql`
- `backend/src/main/java/edu/gatech/sip6321/account/OrganizationRole.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/AddAdminRequest.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/CreateOrganizationRequest.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/CreatedOrganization.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/ManagementAttemptLimiter.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/ManagementMember.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationAuthorization.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationManagementController.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationManagementService.java`
- `backend/src/test/java/edu/gatech/sip6321/OrganizationManagementMigrations.java`
- `backend/src/test/java/edu/gatech/sip6321/organization/OrganizationAuthorizationTest.java`
- `backend/src/test/java/edu/gatech/sip6321/organization/OrganizationManagementControllerTest.java`
- `backend/src/test/java/edu/gatech/sip6321/organization/OrganizationManagementPersistenceIT.java`

### Modified

- `README.md`
- `backend/ACCOUNTS.md`
- `backend/OPPORTUNITIES.md`
- `backend/ORGANIZATIONS.md`
- `backend/PROFILE.md`
- `backend/SIGNUP.md`
- `backend/scripts/profile-smoke.py`
- `backend/src/main/java/edu/gatech/sip6321/account/User.java`
- `backend/src/main/java/edu/gatech/sip6321/account/UserRepository.java`
- `backend/src/main/java/edu/gatech/sip6321/auth/ProfileResponse.java`
- `backend/src/main/java/edu/gatech/sip6321/opportunity/OpportunityExceptionHandler.java`
- `backend/src/main/java/edu/gatech/sip6321/opportunity/OpportunityService.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationController.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationExceptionHandler.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationFailure.java`
- `backend/src/main/java/edu/gatech/sip6321/organization/OrganizationService.java`
- `backend/src/test/java/edu/gatech/sip6321/account/UserPersistenceIT.java`
- `backend/src/test/java/edu/gatech/sip6321/auth/AuthenticationControllerTest.java`
- `backend/src/test/java/edu/gatech/sip6321/auth/ProfileControllerTest.java`
- `backend/src/test/java/edu/gatech/sip6321/opportunity/OpportunityControllerTest.java`
- `backend/src/test/java/edu/gatech/sip6321/opportunity/OpportunityPersistenceIT.java`
- `backend/src/test/java/edu/gatech/sip6321/opportunity/OpportunityServiceTest.java`
- `backend/src/test/java/edu/gatech/sip6321/organization/OrganizationControllerTest.java`
- `backend/src/test/java/edu/gatech/sip6321/organization/OrganizationPersistenceIT.java`
- `backend/src/test/java/edu/gatech/sip6321/organization/OrganizationServiceTest.java`
- `docs/api.md`
- `docs/architecture.md`
- `docs/data-model.md`
- `docs/design/ui-spec.md`
- `docs/design/user-flows.md`
- `docs/security.md`
- `frontend/src/App.test.tsx`
- `frontend/src/api/auth.test.ts`
- `frontend/src/api/auth.ts`
- `frontend/src/api/organizations.ts`
- `frontend/src/pages/ProfileOrganization.tsx`

## Final verification record — November 12, 2026

Verified with Temurin JDK 21.0.5, Node 24.13.0, and disposable PostgreSQL 17 containers:

| Command | Ordinary backend tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw verify` | 441 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 441 passed | 48 passed | BUILD SUCCESS |

Both final commands passed against the completed Java source with zero failures, errors, or skips.
Spotless and executable JAR packaging passed. The 48 integration tests include nine new management
cases, nine existing organization cases, twelve opportunity cases, and eighteen account cases.
They cover real V001–V004 constraints, partial owner uniqueness, multiple ADMINs, existing-record
preservation, explicit single/multiple/orphan legacy mappings, fail-closed unreconciled migrations,
read-only preflight SQL execution, concurrent creation/assignment, transactional audit rollback,
and the full Sarah/Mike grant/use/remove/revocation scenario. Existing signup/login/profile,
registration configuration, future-only publication and upcoming-list regressions passed.
The ordinary suite includes 35 new management controller cases, two authorization/input unit tests,
and OWNER/ADMIN compatibility assertions in the existing service/controller suites.

Frontend `npm ci`, `npm run typecheck`, `npm run lint`, `npm run format:check`, all 42 tests, and
`npm run build` passed. Seven new profile-contract cases cover null/OWNER/ADMIN and malformed
associations. The profile smoke script passed syntax and mocked volunteer/OWNER/ADMIN flows with
no network calls and no credential output. No live browser/Auth/Supabase/Render smoke test occurred.

Intermediate backend runs passed their tests but failed Spotless after in-progress review edits;
formatting was reapplied before both successful final commands above. Backend verification used
local test permissions for JVM attachment, local HTTP stubs, and Docker; no remaining environment
limitation prevented the requested checks.

Unstaged/staged whitespace checks, untracked-file whitespace review, changed/new-file credential
review, exact embedded SQL comparison, and applied-migration byte comparison against origin/main
passed. All new local documentation links resolve. Historical links to the absent decision register
and readiness report remain unresolved; neither document's contents were invented. No live SQL,
production credentials/configuration changes, deployment, external notification, commit, push,
merge, or PR mutation occurred. Work is uncommitted on `feature/organization-management`, created
from the fetched main revision containing merged PRs #17 and #19.

## Main compatibility merge — November 15, 2026

Integrated main revision `938743d`, including PRs #20–#23 (organization creation API/UI,
organization profile editing, and opportunity publishing/listing UI), without changing V001–V004.
Resolved six textual conflicts plus semantic overlaps. There is one POST /api/organizations route,
served by OrganizationManagementController with the approved transactional OWNER/audit behavior.
The older global-role-mutating creation path is superseded. Main's verified-identity extraction
and account-lock helpers remain available. Incoming creation/authentication/rollback/concurrency
regressions were retained and updated for OWNER assignment with an unchanged global role.

Main's existing screens are preserved. The creation adapter validates and unwraps the ownership
response; the existing edit/publish controls accept the matching organization's OWNER or ADMIN;
unassociated registered users can create regardless of global role. Frontend regressions exercise
both roles through profile editing and opportunity publishing, creation/profile refresh, safe
conflict recovery, and read-only unrelated organizations. No new ADMIN-team UI was introduced.

| Command | Ordinary backend tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| Affected organization/profile tests | 101 passed | Not requested | BUILD SUCCESS |
| `./mvnw verify` | 448 passed | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 448 passed | 50 passed | BUILD SUCCESS |

All final backend runs had zero failures, errors, or skips and passed formatting/packaging.
The first PostgreSQL attempt failed at container initialization because Docker Desktop was stopped;
no database test methods ran. After Docker Desktop was started, the full final command above passed.
All containers were disposable; no live Supabase database or credentials were used.

Frontend `npm ci`, typecheck, lint, format:check, all 58 tests, and production build passed.
The original feature changed 49 files (33 modified, 16 added). Relative to current main, compatibility
updates to the incoming controller, frontend organizations API, and profile-creation screen bring
the feature diff to 52 files (36 modified, 16 added); this excludes teammate-only UI additions.
The inventory above reflects that current diff. New local documentation links, credential-pattern
review, whitespace checks and embedded SQL comparisons passed. The historical missing decision
register/readiness-report links remain unchanged. No deployment or production SQL occurred.
