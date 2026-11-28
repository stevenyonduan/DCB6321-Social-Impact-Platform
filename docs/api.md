# API and interfaces — Detailed Design Document requirements

## Interest catalog and selections

These endpoints require a verified Bearer token and a persisted application account. The
organization routes also require an OWNER or ADMIN association with the organization.

| Method | URL | Behavior |
| --- | --- | --- |
| GET | `/api/interests` | List the curated interest catalog |
| GET | `/api/users/me/interests` | Read the caller's personal selections |
| PUT | `/api/users/me/interests` | Replace the caller's personal selections |
| GET | `/api/organizations/me/interests` | Read the caller's organization selections |
| PUT | `/api/organizations/me/interests` | Replace the caller's organization selections |

Each successful response is a `200` JSON array of `{ "id": "<UUID>", "name": "<label>" }`
objects sorted by name. The organization ID comes from the caller's persisted association.
Send a PUT with `Content-Type: application/json` and `{ "interestIds": ["<UUID>"] }`.
Send an empty array to clear the selections. Duplicate IDs are treated as one selection.
An unknown ID returns `400 UNKNOWN_INTEREST` and preserves all previous selections.
Missing or malformed input returns `400 INVALID_REQUEST`; non-managers receive `403 FORBIDDEN`
on the organization routes. All responses use `Cache-Control: no-store`.

[V007](../backend/db/migrations/V007__create_interests.sql) is an additive, manual migration.
Apply it once to the target database before the backend release that uses it, and verify that
the backend database role has the required table access. When a merge triggers deployment, apply
the reviewed migration before merging. The tables are accessed through the backend.

## Standalone volunteer posts and interactions

The [social posts contract](../backend/SOCIAL_POSTS.md) documents authenticated backend-only
post creation (JSON text or multipart JPEG/PNG), feed and individual retrieval, author editing/deletion,
image replacement/removal, idempotent LIKE/SAVE, private `/api/users/me/saved-posts`, and text-only
comment creation/listing/author deletion. Routes are under `/api/posts`; all require an existing
verified application account, including OWNER/ADMIN volunteers. Images have an inclusive 5 MiB
limit and optional server-only Storage setup. SQL pages return items/page/size/hasNext with bounded
stable ordering. V006 is additive and manually reviewed/applied; no live migration runs at startup.
The linked guide contains every endpoint, full request/response examples, errors, frontend guidance,
and cleanup limitations. Sharing is stable UUID retrieval for future authenticated frontend routes.
Group posting remains deferred pending SCRUM-15; email-verification enforcement is deferred with
the approved confirmation-disabled signup flow. This does not claim full SCRUM-12 completion.

## Approved internal event registration contract

The [complete registration contract](../backend/EVENT_REGISTRATIONS.md) defines authentication,
request/success/error examples, curl commands, pagination and frontend integration for all five routes:

| Method | URL | Success |
| --- | --- | --- |
| POST | `/api/opportunities/{eventId}/registrations/me` | 201 registration |
| GET | `/api/opportunities/{eventId}/registrations/me` | 200 own status (registration or null) |
| DELETE | `/api/opportunities/{eventId}/registrations/me` | 204 empty |
| GET | `/api/users/me/registrations` | 200 own registration/event page |
| GET | `/api/organizations/me/opportunities/{eventId}/registrations` | 200 attendee page |

All require a verified Bearer token and persisted application account; no request body. Every
registered account volunteers, including OWNER/ADMIN. POST requires INTERNAL and strictly future
start against server time; DELETE removes only the caller's row before start. Status/personal lists
retain history. Missing event: 404 EVENT_NOT_FOUND. Missing cancellation row: 404
REGISTRATION_NOT_FOUND. Duplicate POST: 409 ALREADY_REGISTERED. Started mutation: 400
EVENT_ALREADY_STARTED. EXTERNAL POST: 400 INTERNAL_REGISTRATION_REQUIRED. Invalid UUID/page/body:
400 INVALID_REQUEST. Attendees require matching persisted OWNER/ADMIN, otherwise 403 FORBIDDEN.
Common Auth/account/database errors and their fixed JSON shape are listed in the linked contract.
All responses use no-store. Pages accept page=0–10000, size=1–100 (defaults 0/20), and return
items/page/size/hasNext ordered by registeredAt then ID ascending. Attendee fields are only ID,
userId, firstName, lastName, registeredAt. INTERNAL UI uses status for Register/Registered;
EXTERNAL uses registrationUrl. [Runnable backend demo](demo-internal-registration-backend.md).

## Approved organization management contract

[Organization management](../backend/ORGANIZATION_MANAGEMENT.md) is the current complete contract,
superseding global ORGANIZATION-role authorization for this scope. Everyone retains volunteer access.
`POST /api/organizations` creates an organization and OWNER atomically (201).
`POST /api/organizations/me/admins` adds an existing unassociated registered user as ADMIN (OWNER only, 204).
`GET /api/organizations/me/admins` lists the caller's team (OWNER/ADMIN, 200).
`DELETE /api/organizations/me/admins/{userId}` removes an associated ADMIN (OWNER only, 204).
The own-profile response preserves its former fields and adds nullable `organizationRole`.
See the guide for complete examples, validation, safe conflicts, audit, throttle and deployment order.

## Subsequently approved opportunity contract

`POST /api/organizations/me/opportunities` returns 201 for a persisted organization OWNER or ADMIN.
The server derives organizationId/creatorId; unknown fields are rejected.
`GET /api/organizations/{organizationId}/opportunities?page=0&size=20` returns an authenticated
paginated object ordered by event_start/id ascending, including upcoming events only. Page is 0–10000,
size 1–100. See [opportunities](../backend/OPPORTUNITIES.md) for complete JSON/TypeScript contracts,
validation, permissions, V003, tests, and manual deployment. Global roles alone grant no publishing authority.
The approved PR #19 extension requires starts strictly after the injected backend clock and
adds registrationType (INTERNAL default when omitted, or EXTERNAL) and registrationUrl (null
for INTERNAL; required valid HTTP(S) destination for EXTERNAL) to creation/list responses.
Explicit invalid/null types fail rather than default. SQL filters upcoming events before paging;
historical rows remain stored. These are approved extensions to the original DDD, not original
schema fields. The owner confirms V001–V003 are applied; do not edit/rerun them. Additive V004 is required for the new organization role model.

## Subsequently approved authentication contracts

The organization API provides authenticated `POST /api/organizations` for a signed-in
registered account without an organization, regardless of global role. It atomically creates an
unverified organization and links the caller as OWNER while preserving volunteer permissions. Signup remains separate. The organization-profile endpoints are
`GET /api/organizations/{id}`, `GET /api/organizations/me`, and owner-authorized
`PATCH /api/organizations/{id}`. See [organization profiles](../backend/ORGANIZATIONS.md)
for JSON/TypeScript contracts, errors, V002 and manual deployment. Only persisted organization OWNER/ADMIN accounts associated with the target can edit; global ADMIN has no override. Organization creation is now supported through the management contract above.

The project owner approved and authorized `POST /api/auth/signup` with email confirmation disabled,
STANDARD-only account creation, server-side validation of the returned Supabase identity, and
allowlisted JSON session delivery after application persistence. The exact contract, errors,
configuration, and recovery limitations are in [backend/SIGNUP.md](../backend/SIGNUP.md).
The subsequently approved `POST /api/auth/login` contract is documented in
[backend/LOGIN.md](../backend/LOGIN.md). The subsequently approved `GET /api/users/me` contract is documented in
[backend/PROFILE.md](../backend/PROFILE.md). It accepts a Supabase Bearer access token, verifies
identity through the existing Auth client, and returns only that UUID's application profile with
`Cache-Control: no-store`. SCRUM-45 additionally approves `PATCH /api/users/me` below.
The DDD transcription below remains source history; endpoint details are resolved only for the
authorized signup, login, authenticated-profile, personal-profile-update, and organization-profile tickets.

### Own personal profile update — SCRUM-45

`PATCH /api/users/me` uses `Authorization: Bearer <access_token>` and `Content-Type: application/json`.
Only the Supabase-verified UUID selects the account, regardless of supplied query parameters.
All existing account roles retain volunteer access. The full contract and development testing
instructions are in [backend/PROFILE.md](../backend/PROFILE.md#personal-profile-updates-scrum-45).

Allowed properties are `firstName` and `lastName` (nonblank strings of at most 100 Unicode code
points), and `bio` (string or null). Omission preserves a value; `bio: null` clears it. Null names,
empty/Unicode-whitespace-only names, invalid Unicode/NUL, malformed JSON, incorrect types,
empty objects and unknown properties are rejected as HTTP 400 `INVALID_REQUEST`. Valid values
are preserved exactly without trimming or truncation. No bio column limit or migration is added;
the entire UTF-8 JSON request is capped at 16,384 bytes (413 `REQUEST_TOO_LARGE`). Non-JSON
content types receive 415 `UNSUPPORTED_MEDIA_TYPE`.

Example request:

```json
{"firstName":"Jordan","bio":"I enjoy volunteering at community gardens."}
```

Example HTTP 200 response, using the unchanged GET representation:

```json
{
  "id":"b6034174-d377-4ad4-b96a-6777660143e6",
  "email":"student@example.com",
  "role":"STANDARD",
  "firstName":"Jordan",
  "lastName":"Student",
  "bio":"I enjoy volunteering at community gardens.",
  "avatarUrl":null,
  "organizationId":null,
  "organizationRole":null,
  "createdAt":"2026-11-01T18:00:00Z"
}
```

Prohibited properties include `id`, `email`, `role`, `organizationId`, `organizationRole`,
`createdAt`, `avatarUrl`, passwords and any Auth/session information. For example,
`{"firstName":"Jordan","role":"ADMIN","organizationRole":"OWNER","organizationId":null}`
returns 400 and applies none of the changes. There is no other-account profile update route.
Updates lock the caller's row and commit atomically; protected columns and omitted values remain unchanged.

Errors retain the `{ "code": "STABLE_CODE", "message": "Safe message." }` convention:
401 `UNAUTHORIZED` for missing/malformed/expired credentials (with `WWW-Authenticate: Bearer`),
429 `AUTH_RATE_LIMITED`, 502 `AUTH_RESPONSE_INVALID`, 503 `AUTH_UNAVAILABLE`,
503 `ACCOUNT_PROVISIONING_REQUIRED` for a verified identity without an account,
503 `ACCOUNT_UNAVAILABLE` for database/transaction failures, and 500 `INTERNAL_ERROR` for
unexpected errors. All successful and error responses use `Cache-Control: no-store`.
The SCRUM-44 frontend should send only changed fields and adopt the full successful response;
the frontend editor is outside SCRUM-45.

Source: [Detailed Design Document](../Detailed%20Design%20Doc.pdf) (DDD), pp. 5–9 and 12–19. This document records the interface requirements established by the DDD. The DDD does not define individual endpoint paths, HTTP methods, request/response schemas or error contracts; those details remain unspecified rather than being supplied as proposed contracts here.

## Communication and backend responsibilities

The DDD establishes the following:

| Requirement | Source |
| --- | --- |
| React + TypeScript provides the frontend; Java + Spring Boot provides the REST API and business logic | pp. 7–9, Figures 1–3 |
| The frontend sends requests to the backend through a RESTful API over HTTPS | p. 19, Data Exchange |
| Data is exchanged primarily in JSON format | p. 19, Data Exchange |
| The backend processes requests, applies application logic and interacts with Supabase PostgreSQL to retrieve, create, update or delete records | p. 19, Data Exchange |
| Supabase Authentication manages user login and password security | p. 19, Data Exchange |
| Access controls restrict sensitive operations according to roles and permissions, including organization event management and hour verification | pp. 7 and 19 |
| Uploaded image files are stored in Supabase Storage; database records contain references to those files | p. 19, File Use |

These requirements describe the system's interfaces and responsibilities. They do not establish an API base path, token header format, JSON property naming convention or a one-to-one mapping between database tables and API resources.

## Functional interface areas

The five logical backend service areas come from Figure 2 (p. 8) and the component responsibilities on p. 9. Organizations are covered by account/profile responsibilities. Messaging and administration are described in the DDD, but their placement within the five service areas is not specified.

| Area | Functionality described by the DDD | Related approved data | Source |
| --- | --- | --- | --- |
| Accounts and profiles | Volunteer and nonprofit accounts/profiles; profile information and interests | users, interests, user_interests | pp. 6, 9, 12–13 |
| Organizations | Nonprofit profiles, interests/categories and volunteer membership in organizations/groups | organizations, organization_interests, organization_members; users.organization_id | pp. 6, 9, 12–14 |
| Opportunities and RSVPs | Browse, search, filter and RSVP to volunteer opportunities; organizations publish opportunities | events, event_registrations | pp. 6, 9, 16–17 |
| Social feed | Community posts and social interactions, including text/image posts, likes, saves and text-only comments | posts, post_interactions, post_comments | pp. 6, 9, 15–16 |
| Recommendations | Personalized volunteer opportunities/content using interests and preferences | User and organization interest relationships | pp. 6, 9, 13–14 |
| Volunteer hours and verification | Manual volunteer-hour submissions and verification | logged_hours | pp. 6, 9, 17, 19 |
| Messaging | Basic direct messages with sender, recipient and text/image content | messages | p. 18 |
| Administration | Different administrative permissions and oversight of user posts/comments | users.role; posts, post_comments | pp. 6–7, 12 |

Following and sharing are named on p. 6, but the DDD does not define their API behavior or persistence structure. The documented post_interactions values are LIKE and SAVE (p. 15); following/sharing must not be represented as additional approved values. See D04 in [development decisions](development-decisions.md).

This is system scope, not a selected MVP feature list or implementation order.

## Data constraints relevant to interfaces

The approved schema constrains the data the application manages. It does not prescribe which fields are accepted or returned by each API operation. Full column definitions are in [data-model.md](data-model.md).

| Source-backed constraint | Interface implication | Source |
| --- | --- | --- |
| users.id matches Supabase auth.users.id; roles are STANDARD, ADMIN and ORGANIZATION | Account identity and role handling must be consistent with these records; provisioning and role-assignment mechanics are not specified | p. 12 |
| Junction tables use composite primary keys | Stored interest selections, memberships, registrations and interactions cannot duplicate the same key combination; duplicate-request behavior is not specified | pp. 10, 13–17 |
| Posts require either text or image content through application logic | Post handling must enforce the documented content requirement | p. 15 |
| post_interactions permits LIKE and SAVE | Interaction handling uses the documented values | p. 15 |
| Comments contain required text content | Comment handling respects the text-only design | p. 16 |
| Events belong to organizations and contain required title, description, start/end times and location | Opportunity handling respects the event schema; time-order validation and cancellation policies are not specified | p. 16 |
| logged_hours contains hours as NUMERIC(5,2), organization/volunteer references, and PENDING/APPROVED/DENIED status with default PENDING | Hour handling respects these fields, types and statuses; positive-only validation and review-transition rules are not specified | p. 17 |
| Messages require either text or image content through application logic | Messaging handling must enforce the documented content requirement | p. 18 |
| Uploaded images support JPEG/PNG and undergo type/size validation | File handling must support the approved formats and validation; upload size limits and interface details are not specified | p. 19 |

The database's UUID, VARCHAR, TIMESTAMPTZ and NUMERIC types are documented in the data model. Their exact JSON representations, field exposure and serialization rules are not defined by the DDD.

## Authentication, authorization and privacy

The DDD requires login to access the platform and different permissions for volunteers, admins and nonprofits (p. 7). It identifies saved posts as private account information and requires authentication/authorization protection for personal information, profiles and messages (pp. 7 and 19).

Authorized organization accounts can manage events and verify volunteer hours (p. 19). The event table refers to an admin creator (p. 16), and the hours table describes admin verification (p. 17). The precise ADMIN/ORGANIZATION permission matrix needs clarification; this document does not resolve that ambiguity or assign management rights to ordinary members.

The DDD does not specify bearer-token transport, JWT verification configuration, which profile fields are visible to other users, or an exact message-access policy. Refer to [security.md](security.md) for the security correction and separately labeled implementation proposals; those proposals are not endpoint contracts approved by the DDD.

The plaintext-password statement on p. 7 is superseded by the user's explicit security correction: Supabase Auth manages password security. No application plaintext-password storage is permitted.

## Interface details not specified in the DDD

The following remain open for team definition and review:

- Endpoint paths/base path, HTTP methods and operation-specific success status codes.
- Request and response fields, public/private field exposure, JSON naming, null handling and timestamp/decimal serialization.
- Error response structure and operation-specific validation/conflict behavior.
- Search/filter parameters, pagination, ordering and recommendation ranking.
- Account/profile provisioning, organization onboarding and role assignment.
- Upload operations, maximum file size, storage policies and private-file access.
- RSVP cancellation, membership admission/leave, editing/deletion and moderation workflows.
- Hour-review transitions and reviewer permissions; messaging transport and access rules.

These omissions are limitations of the supplied design, not approved defaults. D02 and D04–D11 in [development-decisions.md](development-decisions.md) track the related questions. The source-backed interface requirements above are documented, but endpoint-level implementation readiness requires these contracts to be defined for the team's approved scope.

## Existing development health endpoint

The current scaffold separately implements GET /api/health with no request body or authentication and a 200 JSON response of {"status":"UP"}. This was permitted by the readiness request; it is not a product endpoint specified in the DDD. It checks process availability, not database, Auth or Storage connectivity. No product endpoints are implemented by this readiness work.
