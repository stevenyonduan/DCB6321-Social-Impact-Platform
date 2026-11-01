# Security and privacy

Authority: DDD pp. 7 and 19, the approved roles/schema on pp. 12–18, and the user's explicit password correction. Detailed policies below marked **PROPOSED** need team review; the starter implements no authentication or permission system.

## Authentication and password correction

Supabase Auth must manage passwords and sessions. The plaintext-password sentence on DDD p. 7 is superseded; no raw password belongs in application tables, source, logs or API responses. Flag the PDF for correction (D01). Supabase stores salted password hashes through Auth; the application delegates this responsibility rather than storing raw passwords or maintaining a second password store. Supabase documents its [password security](https://supabase.com/docs/guides/auth/password-security); do not implement a separate password store.

React obtains a Supabase session; Spring verifies bearer access-token signatures using the project's configured signing keys, expected issuer/audience and expiry. Resolve the validated `sub` to `users.id`; do not merely decode a JWT or trust body IDs/roles. Validate signing configuration against [Supabase signing-key documentation](https://supabase.com/docs/guides/auth/signing-keys), including key rotation. Fail closed on invalid/expired tokens or missing profile. Signing algorithm, lifecycle, account creation synchronization and role provisioning are D07. Frontend checks are advisory; backend authorization is mandatory on every protected request.

## Authorization boundaries

The design requires login for platform access, distinct STANDARD/ADMIN/ORGANIZATION permissions, private saved posts, moderation of posts/comments and authorized organization management/verification. Exact matrix is **PROPOSED**, pending D02:

| Role | Intended permitted actions | Boundary |
| --- | --- | --- |
| STANDARD | Own profile/interests, own RSVP/membership, own posts/comments/interactions, own hours and participant messages | Cannot assign role, organization ownership or verification identity |
| ORGANIZATION | Own account plus authorized organization profile/events and hour review | Must match `users.organization_id`; membership alone grants no management rights |
| ADMIN | Authorized moderation; organization events and hour review where assigned | Global-vs-organization scope remains unresolved; never infer universal access |

Resource ownership must be checked after identity validation. Derive author/sender/volunteer/reviewer IDs from identity. Verify each event and hour record belongs to the organization the reviewer can manage. Do not let a user promote themselves or set `is_verified`. **PROPOSED:** forbid self-verification and only transition PENDING submissions once; clarify review reversals (D10). Authorize organization-linked posts separately from ordinary posts.

## Data privacy and moderation

Only return public profile fields approved by the team; email, saved items, private account data and hour histories require explicit access policy. Message reads are limited to sender/recipient; never grant automatic administrator access to message contents. DDD specifies admin monitoring of posts/comments, not messages. Proposed deletion endpoints require approved moderation scope, delete/FK behavior and audit/retention rules before implementation. Avoid token, message content, database URL/password and personal-data logging. Use parameterized database access and output escaping; do not render user text as trusted HTML.

## Files and transport

JPEG/PNG images and file type/size validation are approved (DDD p. 19). **PROPOSED safeguards:** inspect actual file signatures, enforce a team-approved byte limit server-side, generate object names, check ownership, restrict buckets and MIME types, and use authorized access/signed URLs for private message files. A browser-supplied MIME type or URL alone is insufficient. Bucket policy, limits, retention and the mismatch between signed URL length/lifetime and `VARCHAR(255)` references are D06. Do not invent volunteer-evidence storage.

HTTPS is required for hosted frontend/API/Auth/Storage traffic. Per the October 29, 2026 university-project deployment request, Render supports `sslmode=require`: database TLS encryption is mandatory, but this mode does not verify the server certificate or hostname and needs no separate CA file. Optional `sslmode=verify-full` remains supported with a trusted CA file; see [deployment configuration](../backend/ACCOUNTS.md#render-account-table-access). Loopback HTTP is acceptable for the scaffold. The backend supports optional exact-origin CORS configuration for `/api/**`; use the Vite development proxy locally. Rate limits and session storage strategy still require review.

## Configuration and database access

Actual `.env` files and credentials are ignored. Only public API URLs and Supabase publishable keys may use `VITE_*`; all such values become public build assets. Never put database passwords, signing secrets or Supabase service-role/secret keys in frontend code. Spring reads exported environment variables; it does not automatically load `.env`. Use separate development credentials, rotate any exposed secrets and avoid printing them in logs.

The [account persistence ticket](../backend/ACCOUNTS.md) adds an internal JPA entity/repository/service and V001, which the project owner successfully applied manually in Supabase. The users table has its Auth UUID foreign key and RLS enabled by that migration, with browser-role table access revoked and no browser policies introduced. No account endpoints, passwords, or live credentials are added. Database integration is opt-in; no startup SQL runs. All 70 backend tests passed locally, including 10 tests against an isolated PostgreSQL container; the coding agent did not connect to Supabase. Live Render account-table permissions remain unverified. Backend access should use least-privilege database roles and parameterized queries. Broader Data API access and how Storage policies consume identity remain D09 decisions. Do not treat a privileged backend database connection as user authorization. Basic builds/tests remain credential-free.
