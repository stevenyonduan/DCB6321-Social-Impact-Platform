# Security and privacy

## Approved signup decision

The project owner subsequently approved a single-step backend signup with Supabase email
confirmation disabled. Signup validates the returned bearer session through Supabase's authenticated
user API before creating a STANDARD account, then returns an allowlisted session only after the
application transaction commits. It does not prove email ownership. Persistent partial failures
require operator reconciliation; Auth identities are never deleted as compensation. See the
[signup security, setup, and recovery contract](../backend/SIGNUP.md). This supersedes earlier
verification/completion proposals for signup only. General protected-route JWT/authorization design
below remains separate; the backend ticket adds no general authentication filter. The subsequently authorized frontend integration uses these endpoints and Supabase session lifecycle management.

The subsequently approved [authenticated profile endpoint](../backend/PROFILE.md) verifies each
Bearer token through Supabase's authenticated user API using the existing Auth client, then reads
only that verified UUID's application account. It does not use locally decoded JWT claims or add
a general security filter. Own-profile email and the other existing account fields are explicitly
authorized by this ticket; public profile exposure and broader authorization remain unresolved.

Authority: DDD pp. 7 and 19, the approved roles/schema on pp. 12–18, and the user's explicit password correction. Detailed policies below marked **PROPOSED** need team review; the starter implements no authentication or permission system.

## Authentication and password correction

Supabase Auth must manage passwords and sessions. The plaintext-password sentence on DDD p. 7 is superseded; no raw password belongs in application tables, source, logs or API responses. Flag the PDF for correction (D01). Supabase stores salted password hashes through Auth; the application delegates this responsibility rather than storing raw passwords or maintaining a second password store. Supabase documents its [password security](https://supabase.com/docs/guides/auth/password-security); do not implement a separate password store.

React obtains a Supabase session; Spring verifies bearer access-token signatures using the project's configured signing keys, expected issuer/audience and expiry. Resolve the validated `sub` to `users.id`; do not merely decode a JWT or trust body IDs/roles. Validate signing configuration against [Supabase signing-key documentation](https://supabase.com/docs/guides/auth/signing-keys), including key rotation. Fail closed on invalid/expired tokens or missing profile. Signing algorithm, lifecycle, account creation synchronization and role provisioning are D07. Frontend checks are advisory; backend authorization is mandatory on every protected request.

## Authorization boundaries

The subsequently approved [organization profile API](../backend/ORGANIZATIONS.md) resolves a
bounded portion of D02: authenticated application accounts may read the six organization profile
fields. Authenticated creation grants ownership only to a persisted STANDARD account without an
organization. The backend locks that account and assigns its ORGANIZATION role and new association
in the same transaction as the insert. The request cannot supply role, association, or verification.
For editing, only persisted ORGANIZATION accounts whose users.organization_id matches the target may
edit name/description/website. ADMIN has no edit override. Unknown/protected patch fields are
rejected. V002 enables organizations RLS and revokes PUBLIC/anon/authenticated privileges without
adding browser policies. Following/membership, verification and broader ADMIN policy remain open.

The design requires login for platform access, distinct STANDARD/ADMIN/ORGANIZATION permissions, private saved posts, moderation of posts/comments and authorized organization management/verification. Exact matrix is **PROPOSED**, pending D02:

| Role | Intended permitted actions | Boundary |
| --- | --- | --- |
| STANDARD | Own profile/interests, own RSVP/membership, own posts/comments/interactions, own hours and participant messages | Cannot assign role, organization ownership or verification identity |
| ORGANIZATION | Own account plus authorized organization profile/events and hour review | Must match `users.organization_id`; membership alone grants no management rights |
| ADMIN | Authorized moderation; organization events and hour review where assigned | Global-vs-organization scope remains unresolved; never infer universal access |

Resource ownership must be checked after identity validation. Derive author/sender/volunteer/reviewer IDs from identity. Verify each event and hour record belongs to the organization the reviewer can manage. Do not let a user promote themselves or set `is_verified`. **PROPOSED:** forbid self-verification and only transition PENDING submissions once; clarify review reversals (D10). Authorize organization-linked posts separately from ordinary posts.

The subsequently approved [opportunity API](../backend/OPPORTUNITIES.md) extends the bounded D02
policy: only a persisted ORGANIZATION account with an existing associated organization may
publish. Both organization and creator IDs derive from the verified account; protected fields
are rejected. ADMIN has no override, and is_verified is not a publication prerequisite. Any
authenticated application account may list an organization’s events. V003 enables events RLS,
revokes browser-role privileges and adds no browser policies. Registration type does not change any permission. The approved
PR #19 extension validates future starts against an injected server clock, rejects client time,
and lists only upcoming events in SQL while retaining historical rows. EXTERNAL registration
URLs are untrusted references: the API validates absolute HTTP(S), host, credentials, ports, and
unsafe control/Unicode characters without fetching or granting volunteer-data access. HTTP is
permitted; HTTPS is recommended. A future frontend must show the destination domain, use safe
external links, avoid automatic redirects and transmitting tokens/sensitive data, and never count
a link click as registration. INTERNAL is the omission default; no RSVP/registration records or
provider integrations are implemented. Broader ADMIN/verification and D06 Storage policy remain open.

## Data privacy and moderation

Only return public profile fields approved by the team; email, saved items, private account data and hour histories require explicit access policy. Message reads are limited to sender/recipient; never grant automatic administrator access to message contents. DDD specifies admin monitoring of posts/comments, not messages. Proposed deletion endpoints require approved moderation scope, delete/FK behavior and audit/retention rules before implementation. Avoid token, message content, database URL/password and personal-data logging. Use parameterized database access and output escaping; do not render user text as trusted HTML.

## Files and transport

JPEG/PNG images and file type/size validation are approved (DDD p. 19). **PROPOSED safeguards:** inspect actual file signatures, enforce a team-approved byte limit server-side, generate object names, check ownership, restrict buckets and MIME types, and use authorized access/signed URLs for private message files. A browser-supplied MIME type or URL alone is insufficient. Bucket policy, limits, retention and the mismatch between signed URL length/lifetime and `VARCHAR(255)` references are D06. Do not invent volunteer-evidence storage.

HTTPS is required for hosted frontend/API/Auth/Storage traffic. Per the October 29, 2026 university-project deployment request, Render supports `sslmode=require`: database TLS encryption is mandatory, but this mode does not verify the server certificate or hostname and needs no separate CA file. Optional `sslmode=verify-full` remains supported with a trusted CA file; see [deployment configuration](../backend/ACCOUNTS.md#render-account-table-access). Loopback HTTP is acceptable for the scaffold. The backend supports optional exact-origin CORS configuration for `/api/**`; use the Vite development proxy locally. Rate limits and session storage strategy still require review.

## Configuration and database access

Actual `.env` files and credentials are ignored. Only public API URLs and Supabase publishable keys may use `VITE_*`; all such values become public build assets. Never put database passwords, signing secrets or Supabase service-role/secret keys in frontend code. Spring reads exported environment variables; it does not automatically load `.env`. Use separate development credentials, rotate any exposed secrets and avoid printing them in logs.

The [account persistence ticket](../backend/ACCOUNTS.md) adds an internal JPA entity/repository/service and V001, which the project owner successfully applied manually in Supabase. The users table has its Auth UUID foreign key and RLS enabled by that migration, with browser-role table access revoked and no browser policies introduced. No account endpoints, passwords, or live credentials are added. Database integration is opt-in; no startup SQL runs. All 70 backend tests passed locally, including 10 tests against an isolated PostgreSQL container; the coding agent did not connect to Supabase. Live Render account-table permissions remain unverified. Backend access should use least-privilege database roles and parameterized queries. Broader Data API access and how Storage policies consume identity remain D09 decisions. Do not treat a privileged backend database connection as user authorization. Basic builds/tests remain credential-free.

## Frontend authentication integration

The user approved persistent browser sign-in across reloads. The frontend sends signup/login
to Spring, adopts the returned access/refresh tokens through the official Supabase browser
client, and uses its standard persistent localStorage session lifecycle with automatic refresh.
This makes tokens accessible to JavaScript: preserve React output escaping and avoid raw HTML,
request/header/body logging, or third-party scripts that can read credentials. Passwords are
not stored in app state or browser storage. Public project URL/publishable key are the only
Supabase settings exposed to Vite. The browser performs no direct application-table operations.

Stored sessions are only credentials, never proof of authorization. The frontend obtains the
application profile from authenticated `/api/users/me` before showing the homepage and clears
private UI on sign-out or expiry. Logout targets the current session, clears this browser even
when remote revocation fails, and reports remote failure. Already-issued access tokens can
remain valid until expiry; logout does not promise immediate token invalidation or sign-out on
other devices. Full backend resource authorization remains required for future features.
