# Standalone volunteer social posts — SCRUM-12 / SCRUM-14

This backend implements personal standalone posts, one optional image, likes, private saves and
text-only comments. Every endpoint requires a valid Supabase Bearer access token and an existing
application account. STANDARD, ORGANIZATION, global ADMIN, organization OWNER and organization
ADMIN all retain personal volunteer capabilities. The service uses `OrganizationAuthorization.account`
and existing `ProfileService` verification, not client identities or decoded claims. No email-confirmation
check is added: the approved signup flow disables confirmation. This is a temporary deviation from
the Iteration Plan requirement and must be revisited when that authentication feature exists.

**Deferred:** group-associated posting (part of SCRUM-12, blocked on SCRUM-15 membership), organization
branding/posting (SCRUM-13), frontend share links/buttons, frontend screens, moderation, following,
notifications, replies, comment editing, share counts and share-event storage. This does **not**
complete every SCRUM-12 acceptance criterion. No organization membership table is added.

## API contract

All responses, including errors, use `Cache-Control: no-store`. Send
`Authorization: Bearer <development-access-token>`. UUIDs are stable. All times are UTC ISO-8601.
Unknown/protected JSON or multipart fields are rejected, even when null. Never send authorId,
userId, id, role, organizationId, createdAt, updatedAt, imageUrl, object paths or external image URLs.
The backend generates IDs, derives authors from Auth and always assigns organization_id NULL.
Organization-associated rows are excluded from reads and mutations in this release.

| Method | Endpoint | Request | Success |
| --- | --- | --- | --- |
| POST | `/api/posts` | JSON `{ "textContent": "Food bank today" }` | 201 post |
| POST | `/api/posts` | multipart `image` and optional `textContent` | 201 post |
| GET | `/api/posts?page=0&size=20` | None | 200 post page |
| GET | `/api/posts/{postId}` | None | 200 post |
| PATCH | `/api/posts/{postId}` | JSON `{ "textContent": "Updated" }` | 200 post |
| DELETE | `/api/posts/{postId}` | No body | 204 empty |
| PUT | `/api/posts/{postId}/image` | multipart `image` only | 200 post |
| DELETE | `/api/posts/{postId}/image` | No body | 200 post |
| PUT / DELETE | `/api/posts/{postId}/like` | No body | 204 empty |
| PUT / DELETE | `/api/posts/{postId}/save` | No body | 204 empty |
| GET | `/api/users/me/saved-posts?page=0&size=20` | None | 200 own post page |
| POST | `/api/posts/{postId}/comments` | JSON `{ "content": "Thank you!" }` | 201 comment |
| GET | `/api/posts/{postId}/comments?page=0&size=20` | None | 200 comment page |
| DELETE | `/api/posts/{postId}/comments/{commentId}` | No body | 204 empty |

Text is preserved exactly; no silent trimming or truncation. A post needs meaningful text (not all
Unicode whitespace/space characters), an image, or both. Comments require meaningful text.
PostgreSQL-incompatible NUL/malformed Unicode and incorrect value types fail validation.
JSON bodies are limited to 16,384 bytes, including UTF-8, syntax and escapes, even without
Content-Length. Multipart text fields are limited to 16,384 UTF-8 bytes. These are operational
request bounds, not new database text-length constraints. Duplicate multipart parts are rejected.
Multipart creation requires exactly one `image`, so use JSON for text-only creation.

PATCH accepts an omitted textContent (`{}` is a no-op), a string, or explicit null. Omission preserves
text; null clears it only if an image remains. A successful change advances updatedAt at microsecond
precision; creation initializes createdAt and updatedAt to the same database NOW(). Empty/no-op
PATCH does not advance updatedAt. Image replacement/addition/removal also advances updatedAt.
Removing the sole content image is rejected; replace it directly or add text first.
Only post authors can change/delete posts or images. Only comment authors can delete their comments,
including when the post belongs to someone else. Comment ID must belong to the URL's post.
Existing standalone resources return 403 for non-author mutations; missing/deleted/group-associated
posts return 404. A mismatched/missing comment returns 404. There is no administrator override.

Likes and saves use separate composite-key records and independent idempotent PUT/DELETE operations.
Repeated PUT/DELETE returns 204 for an existing post. A missing post returns 404 even for DELETE.
Saves never change ownership. Only `/api/users/me/saved-posts` lists a saved collection; there is no
arbitrary-user route. A post exposes only **the caller's** saved/liked state, not other users' saves.

### Response examples

201 creation and 200 retrieval/edit/image responses share this representation:

```json
{
  "id": "8e6b9c58-2902-46ec-96b0-79433fd53848",
  "authorId": "b6034174-d377-4ad4-b96a-6777660143e6",
  "organizationId": null,
  "textContent": "Food bank today",
  "imageReference": null,
  "createdAt": "2026-11-17T00:00:00Z",
  "updatedAt": "2026-11-17T00:00:00Z",
  "likeCount": 0,
  "commentCount": 0,
  "liked": false,
  "saved": false
}
```

An image-only response has `textContent: null` and e.g.
`imageReference: "social-posts/posts/eb2e488c-e865-4cb5-9c52-2dc005de3c8d.png"`.
A multimedia response has both populated. No email, global role, organization-management role,
or other private profile fields are returned. Render all user text as escaped text, never HTML.

Feed and saved responses wrap post objects:

```json
{
  "items": [
    {
      "id": "8e6b9c58-2902-46ec-96b0-79433fd53848",
      "authorId": "b6034174-d377-4ad4-b96a-6777660143e6",
      "organizationId": null,
      "textContent": "Food bank today",
      "imageReference": null,
      "createdAt": "2026-11-17T00:00:00Z",
      "updatedAt": "2026-11-17T00:00:00Z",
      "likeCount": 1,
      "commentCount": 1,
      "liked": true,
      "saved": true
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

Comment creation response (201):

```json
{
  "id": "b6306bf1-c2df-48e0-9f72-749b7669cfb7",
  "postId": "8e6b9c58-2902-46ec-96b0-79433fd53848",
  "authorId": "b6034174-d377-4ad4-b96a-6777660143e6",
  "content": "Thank you!",
  "createdAt": "2026-11-17T00:01:00Z"
}
```

Comment lists use the identical `items/page/size/hasNext` wrapper containing comment objects.
All pages default to page=0, size=20; bounds are page=0–10000, size=1–100. Feed ordering is
createdAt DESC, id DESC; saves use interaction createdAt DESC, postId DESC; comments use
createdAt ASC, id ASC. SQL slices fetch at most size+1 and never load an entire collection.
Post reads compute counts and caller flags in one SQL query with indexed correlated subqueries;
there is no per-post repository loop or N+1. Offset pages can shift with concurrent changes.

### Errors

```json
{"code":"FORBIDDEN","message":"Only the author may change this resource."}
```

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Invalid body/property/value/UUID/page/size or mismatched multipart shape |
| 401 | UNAUTHORIZED | Missing, malformed, rejected or expired Bearer token; includes WWW-Authenticate: Bearer |
| 403 | FORBIDDEN | Existing resource belongs to another author |
| 404 | POST_NOT_FOUND / COMMENT_NOT_FOUND | Missing standalone resource or wrong post/comment pairing |
| 405 | METHOD_NOT_ALLOWED | Unsupported method |
| 413 | REQUEST_TOO_LARGE | JSON, text, file or multipart envelope exceeds its byte limit |
| 415 | UNSUPPORTED_MEDIA_TYPE | Unsupported HTTP content type |
| 415 | INVALID_IMAGE | Empty, invalid, corrupt, unsupported, mismatched or excessive-dimension image |
| 429 | AUTH_RATE_LIMITED | Existing Auth provider rate limit mapping |
| 502 | AUTH_RESPONSE_INVALID | Existing malformed/rejected upstream Auth response mapping |
| 503 | AUTH_UNAVAILABLE | Existing Auth timeout/network/provider failure mapping |
| 503 | ACCOUNT_PROVISIONING_REQUIRED | Verified identity lacks an application account |
| 503 | ACCOUNT_UNAVAILABLE | Existing account database failure mapping |
| 503 | SOCIAL_UNAVAILABLE | Social database or transaction failure, including commit failure |
| 503 | STORAGE_UNAVAILABLE | Storage disabled, unavailable, timed out or rejected the operation |
| 500 | INTERNAL_ERROR | Unexpected sanitized server failure |

Parser/SQL/Storage diagnostics, stack traces, object contents and credentials are not returned.
Request validation can run before authentication; malformed/oversized requests can therefore return
400/413/415 before an Auth check. Existing exact-origin CORS permits Authorization/Content-Type and
GET/POST/PUT/PATCH/DELETE; browser frontend code must not send credentials through query parameters.

## Images and required external setup — D06

Images must decode as JPEG or PNG and match their declared image/jpeg or image/png content type.
File signatures, decoding and decoder warnings are checked. The inclusive file limit is
**5,242,880 bytes (5 MiB)**. Decoding is bounded to 8,192 pixels per dimension and 16,000,000 total
pixels to limit resource use. The servlet spools files to disk immediately and bounds multipart
requests to 5,308,416 bytes (image plus 64 KiB envelope); text validation applies separately.
These finite limits also cover chunked uploads. Original filenames are ignored. Generated UUIDv4
names under `posts/` prevent path traversal, user-selected overwrites and deterministic names.

Storage is optional and **disabled by default**. Text-only posts, reads, interactions and comments
work without it. To enable live image uploads, an authorized operator must separately configure:

- Existing HTTPS `SUPABASE_URL` project origin.
- `SIP_STORAGE_ENABLED=true`.
- `SIP_STORAGE_BUCKET`: existing bucket identifier, 1–100 ASCII letters/digits/underscore/hyphen.
- `SUPABASE_STORAGE_SERVICE_ROLE_KEY`: server-only legacy Supabase service-role JWT with Storage
  upload/delete privileges. The adapter does not accept a modern opaque secret key in this setting.
  Never use this credential as the Auth publishable key, in React, in VITE_*, in source or in logs.
- The bucket's JPEG/PNG and size policies, server permissions, and a team-approved public/private
  image-serving policy. This PR does not choose, create or modify live buckets or policies.

The adapter uses POST `/storage/v1/object/{bucket}/posts/{uuid}.{png|jpg}` without upsert, and DELETE
of that same validated reference. HTTPS configuration, no redirects, 3-second connect and 10-second
complete-exchange deadlines, and discarded response bodies keep the integration bounded. See
[Supabase Storage API](https://supabase.github.io/storage/) and
[server access control](https://supabase.com/docs/guides/storage/security/access-control).

PostgreSQL `image_url VARCHAR(255)` stores **bucket/object-path**, returned as `imageReference`.
It never stores an expiring signed URL. For a separately approved public bucket, frontend integration
can resolve the reference through the official Supabase public URL helper. For a private bucket,
the operator must first approve authenticated read policies or a signed-read mechanism; this PR
does not expose server credentials or implement signed-URL issuance. Until that decision/setup is
complete, the durable reference is the backend contract and private image display remains a live
integration prerequisite. See [Supabase serving assets](https://supabase.com/docs/guides/storage/serving/downloads).
No live Storage or Supabase smoke test has been performed.

### Transactions and cleanup

All mutations are transactional. Post-row write locks serialize child writes, replacement, edits
and deletion. The composite key plus targeted ON CONFLICT DO NOTHING makes interaction inserts
race-safe. Post FKs use ON DELETE CASCADE for comments/interactions, so deletion is atomic.
Other author/organization FKs retain NO ACTION. No browser access bypasses the service rules.

A new uploaded object's cleanup callback is registered before the database write and runs after
rollback, including commit-time failure. Replacement never deletes the old object before commit.
Old-image/post-deletion cleanup runs after commit; cleanup failure cannot undo committed database
work. Upload network failures attempt removal of their generated path because remote success may
be ambiguous. Cleanup accepts only the configured bucket's generated posts/UUIDv4 references.
Warnings are fixed sanitized messages; no reference, token, exception body or user text is logged.

**Residual limitation:** cleanup is best effort without a durable retry/outbox. Process crashes,
remote cleanup failures, unknown commit outcomes, bucket changes and late remote upload completion
can leave orphan objects. A crash after upload but before callback registration is also possible.
Operators must reconcile the configured bucket's generated objects against database references
before deleting any orphan, account for in-flight uploads/transactions, and retain old-bucket
configuration during transitions. There is no automatic orphan sweeper. Multiple concurrent uploads
can still consume resources: per-request limits are enforced, but general rate limiting is deferred.
Storage network operations occur while holding the post transaction/lock; finite timeouts bound
that wait. Existing images cannot be remotely cleaned while Storage is disabled.

## Reviewed additive migration

[V006](db/migrations/V006__create_social_posts.sql) follows V001–V005; none of those files is changed.
It creates only posts, post_interactions and post_comments with the approved columns, UUIDv4 defaults,
database timestamps, nullable organization_id FK and LIKE/SAVE composite key. Initial updated_at
uses NOW() so it equals created_at; later edits use server Clock at PostgreSQL microsecond precision,
advancing by at least one microsecond if necessary. Nonempty post content remains an application
invariant as specified in the DDD. No SHARE, memberships, new roles or schema text-length limits.

Feed, comment, interaction-count and personal-interaction indexes support the documented queries.
RLS is enabled on all three tables with no browser policies; all PUBLIC/anon/authenticated privileges
are revoked. Existing account identity/permissions and registration tables remain unchanged.
Before a separately authorized development deployment, review the **entire** migration and effective
JDBC role's SELECT/INSERT/UPDATE/DELETE grants and RLS access. Explicit application by an operator
must follow V001–V005 with drift checks, backup and post-application verification using the project's
existing manual migration process. Never rerun an applied migration. No startup schema creation or
live migration is performed here. Merely granting privileges does not establish effective RLS access.

D02 (bounded ownership), D04 (backend sharing), D06 (images), D09 (backend-only DB) and D11 (membership)
are the historical decision references in repository specifications. The referenced decision-register
file and updated Iteration Plan were absent from this checkout; no missing contents are invented.
The explicit authorized task resolves the bounded behavior above. D06's live serving policy and
SCRUM-15 group membership remain external/future work.

## Frontend integration and development examples

Frontend developers can use JSON creation for text-only and FormData for images. Let the browser
set the multipart boundary; never manually set multipart Content-Type when sending FormData.
Send only `image` and optional `textContent`. Use the current Supabase access token, handle existing
refresh/expiry flows, and refresh the relevant feed/post/saved page after successful mutations.
Like/save changes can be retried idempotently; creation/comments need care after a network timeout
because they have no idempotency key. No frontend change is included.

Sharing uses the returned stable post UUID. A future frontend may define `/posts/{postId}` and
construct links on its actual origin, then call authenticated GET `/api/posts/{postId}`. Recipients
must log in and have an application account. Missing/deleted posts return 404. The backend has no
frontend domain, share page, clipboard behavior or SHARE tracking.

Run manual requests only against an explicitly authorized **development** database and bucket,
with reviewed V006 applied and existing Auth/account setup. Spring does not load .env automatically.
Keep real token values private and out of shell history/traces. The examples below use placeholders:

```sh
BASE=http://127.0.0.1:8080
TOKEN=DEVELOPMENT_ACCESS_TOKEN
POST_ID=8e6b9c58-2902-46ec-96b0-79433fd53848
COMMENT_ID=b6306bf1-c2df-48e0-9f72-749b7669cfb7
curl -i -X POST "$BASE/api/posts" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data '{"textContent":"Food bank today"}'
curl -i -X POST "$BASE/api/posts" -H "Authorization: Bearer $TOKEN" -F 'image=@development-photo.png;type=image/png'
curl -i -X POST "$BASE/api/posts" -H "Authorization: Bearer $TOKEN" -F 'textContent=Food bank today' -F 'image=@development-photo.jpg;type=image/jpeg'
curl -i "$BASE/api/posts?page=0&size=20" -H "Authorization: Bearer $TOKEN"
curl -i "$BASE/api/posts/$POST_ID" -H "Authorization: Bearer $TOKEN"
curl -i -X PATCH "$BASE/api/posts/$POST_ID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data '{"textContent":"Updated"}'
curl -i -X PUT "$BASE/api/posts/$POST_ID/image" -H "Authorization: Bearer $TOKEN" -F 'image=@development-photo.png;type=image/png'
curl -i -X DELETE "$BASE/api/posts/$POST_ID/image" -H "Authorization: Bearer $TOKEN"
curl -i -X PUT "$BASE/api/posts/$POST_ID/like" -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE "$BASE/api/posts/$POST_ID/like" -H "Authorization: Bearer $TOKEN"
curl -i -X PUT "$BASE/api/posts/$POST_ID/save" -H "Authorization: Bearer $TOKEN"
curl -i "$BASE/api/users/me/saved-posts?page=0&size=20" -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE "$BASE/api/posts/$POST_ID/save" -H "Authorization: Bearer $TOKEN"
curl -i -X POST "$BASE/api/posts/$POST_ID/comments" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data '{"content":"Thank you!"}'
curl -i "$BASE/api/posts/$POST_ID/comments?page=0&size=20" -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE "$BASE/api/posts/$POST_ID/comments/$COMMENT_ID" -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE "$BASE/api/posts/$POST_ID" -H "Authorization: Bearer $TOKEN"
```

Repeat PUT/DELETE interactions, verify independent save/like status, try a second development
account's ownership changes (403), check private saved pages, invalid protected fields (400),
blank text (400), bad image (415), oversized image (413), missing token (401) and deleted post (404).
These mutate development data; clean up only the test account's own posts.

## Automated verification

From backend with JDK 21:

```sh
./mvnw verify
./mvnw -Ppostgres-it verify
```

The ordinary suite requires no database/Storage credentials. The PostgreSQL profile requires Docker
and applies actual V001–V006 scripts to disposable PostgreSQL 17 containers with fake Auth schema
and mocked token/Storage adapters. Real local HTTP tests exercise Storage protocol and servlet
multipart limits; no request is made to live Supabase. See the verification record below for actual
results; commands listed here alone are not a claim that tests ran.

## Exact change inventory

Created:

- [V006__create_social_posts.sql](db/migrations/V006__create_social_posts.sql)
- [SOCIAL_POSTS.md](SOCIAL_POSTS.md)
- [Post.java](src/main/java/edu/gatech/sip6321/social/Post.java)
- [PostComment.java](src/main/java/edu/gatech/sip6321/social/PostComment.java)
- [PostInteraction.java](src/main/java/edu/gatech/sip6321/social/PostInteraction.java)
- [PostRepository.java](src/main/java/edu/gatech/sip6321/social/PostRepository.java)
- [CommentRepository.java](src/main/java/edu/gatech/sip6321/social/CommentRepository.java)
- [InteractionRepository.java](src/main/java/edu/gatech/sip6321/social/InteractionRepository.java)
- [PostResponse.java](src/main/java/edu/gatech/sip6321/social/PostResponse.java)
- [SocialPage.java](src/main/java/edu/gatech/sip6321/social/SocialPage.java)
- [SocialBody.java](src/main/java/edu/gatech/sip6321/social/SocialBody.java)
- [SocialText.java](src/main/java/edu/gatech/sip6321/social/SocialText.java)
- [SocialFailure.java](src/main/java/edu/gatech/sip6321/social/SocialFailure.java)
- [SocialService.java](src/main/java/edu/gatech/sip6321/social/SocialService.java)
- [SocialController.java](src/main/java/edu/gatech/sip6321/social/SocialController.java)
- [SocialExceptionHandler.java](src/main/java/edu/gatech/sip6321/social/SocialExceptionHandler.java)
- [SocialRequestFilter.java](src/main/java/edu/gatech/sip6321/social/SocialRequestFilter.java)
- [ValidatedImage.java](src/main/java/edu/gatech/sip6321/social/ValidatedImage.java)
- [PostImageStorage.java](src/main/java/edu/gatech/sip6321/social/PostImageStorage.java)
- [SupabasePostImageStorage.java](src/main/java/edu/gatech/sip6321/social/SupabasePostImageStorage.java)
- [SocialConfiguration.java](src/main/java/edu/gatech/sip6321/social/SocialConfiguration.java)
- [SocialControllerTest.java](src/test/java/edu/gatech/sip6321/social/SocialControllerTest.java)
- [SocialServiceTest.java](src/test/java/edu/gatech/sip6321/social/SocialServiceTest.java)
- [ValidatedImageTest.java](src/test/java/edu/gatech/sip6321/social/ValidatedImageTest.java)
- [SupabasePostImageStorageTest.java](src/test/java/edu/gatech/sip6321/social/SupabasePostImageStorageTest.java)
- [SocialPersistenceIT.java](src/test/java/edu/gatech/sip6321/social/SocialPersistenceIT.java)

Modified:

- [.env.example](.env.example): optional, placeholder-only server Storage configuration.
- [application.yml](src/main/resources/application.yml): bounded disk-spooled multipart uploads.
- [docs/api.md](../docs/api.md): social API summary and complete-contract link.

All frontend files, existing migrations, Auth/account/profile/organization/opportunity/registration
production code, build dependencies and CI configuration remain unchanged.

## Verification and PR-review status — November 16, 2026

Implementation is ready for **team PR review**, with the live Storage prerequisites and best-effort
cleanup limitation above explicitly outstanding. Source changes remain uncommitted on
`feature/volunteer-social-posts`. The working tree was clean before implementation. No frontend
files or previously applied migrations were changed. No commit, push, merge, deployment, Jira
modification, production access or live database migration occurred.

Verification used local JDK 21.0.2, Mockito, loopback HTTP servers and actual disposable PostgreSQL
17 Docker containers. Commands ran from backend with that JAVA_HOME configured:

| Actual command | Ordinary tests | PostgreSQL tests | Result |
| --- | --- | --- | --- |
| `./mvnw spotless:apply verify` | 568 | Not requested | BUILD SUCCESS |
| `./mvnw -Ppostgres-it verify` | 568 | 74 | BUILD SUCCESS |

Both complete runs had **zero failures, errors or skipped tests**. The new social tests contribute
40 ordinary tests and 12 PostgreSQL integration tests; existing Auth/profile/SCRUM-45/organization/
opportunity/registration regressions passed. Formatting, compilation and executable packaging passed.
The exact-5-MiB and oversized/chunked upload cases also ran through real embedded Tomcat HTTP,
not only MockMvc. HTTP Storage protocol tests used a local fake endpoint, and live Supabase Auth
was mocked in database tests. These results do not certify live bucket policy, service-role grants,
image-serving access, deployed JDBC/RLS grants or live Auth connectivity.

An initial focused run caught a Mockito restubbing error in a test, corrected with doThrow; the
first real-server test compile caught a Spring Boot test annotation package mismatch, corrected
with local.server.port injection. Subsequent focused and full runs passed without weakened or
skipped cases. Final test-only cleanup replaces one deprecated status assertion and documents that
JUnit owns the Testcontainers lifecycle.

Tracked/staged diff whitespace checks passed; untracked source/SQL/documentation were separately
reviewed for whitespace and credential patterns. All local links in this guide resolve. The
API summary's new link resolves. Historical references to the absent decision-register/Iteration
Plan were not fabricated. Frontend checks were not run because no frontend files changed.

Final focused verification after the test-only cleanup also passed:
`./mvnw spotless:apply -Ppostgres-it -Dtest='SocialServiceTest,SocialControllerTest,ValidatedImageTest,SupabasePostImageStorageTest' -Dit.test=SocialPersistenceIT verify`
— 40 ordinary tests plus 12 actual PostgreSQL tests; zero failures/errors/skips, BUILD SUCCESS.
