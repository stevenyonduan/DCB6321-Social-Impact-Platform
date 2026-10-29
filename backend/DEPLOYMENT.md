# Render deployment and readiness

The backend targets Java 21 with Spring Boot 4.1.1 and Maven wrapper 3.9.16.
Docker builds and tests the backend in a Java 21 JDK stage and copies only the
executable JAR to a Java 21 JRE stage. Runtime UID/GID is `10001:10001`.
No frontend files, Node tools, database credentials or CA certificates are baked
into the image. No schema changes or deployments are performed by these files.

## Exact Render settings

| Setting | Value |
| --- | --- |
| Repository | `stevenyonduan/DCB6321-Social-Impact-Platform` |
| Branch | `main` |
| Runtime | Docker |
| Root Directory | `backend` |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | `.` |
| Region | Ohio (US East) |
| Instance | Free (512 MB) |
| Port | Render's `PORT`; optionally set `PORT=8080` |
| Health Check Path | `/api/health` |
| Docker Command | Leave blank; use the image entrypoint |

The Dockerfile path and build context are relative to Root Directory. Do not use
`backend/Dockerfile` with Root Directory already set to `backend`. No separate
build/start command is required. Configure automatic deployments according to the
team's release process; this task does not push or trigger deployments.

References: [Render monorepo paths](https://render.com/docs/monorepo-support),
[Docker services](https://render.com/docs/docker).

## Render environment variables

Configure these database variables in Render's Environment settings:

| Variable | Required | Value |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Yes | `jdbc:postgresql://aws-1-ca-central-1.pooler.supabase.com:5432/postgres?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Yes | `postgres.hbqfvsuilurhgjyizvnv`, as supplied by the team; verify permissions before deployment |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Configure privately in Render; never commit or paste into chat |
| `SPRING_PROFILES_ACTIVE` | Supplied by image | `prod`; explicitly set to `prod` if a previous Render override exists |
| `PORT` | Supplied by Render | Set to `8080` if desired; otherwise Render's supplied value is used |
| `SIP_CORS_ALLOWED_ORIGINS` | Optional | Comma-separated exact frontend origins, e.g. `https://YOUR_APP.vercel.app`; no trailing slash or wildcard |
| `JAVA_TOOL_OPTIONS` | Supplied by image | `-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError`; override only after memory measurements |

No `SUPABASE_URL`, Supabase Auth key or service-role key is consumed by this
scaffold. `SIP_DB_*` variables are only for the existing opt-in local `database`
profile, not production. Replace stale Render overrides using those variables.
Spring does not automatically load `.env` files. Values in frontend `VITE_*`
variables are public and must never contain database credentials or secret keys.

The `prod` profile binds to `0.0.0.0` and uses `PORT`, falling back to `SERVER_PORT`,
then 8080. The default profile outside Docker remains `local`, database-free and
bound to loopback. The previous `render` profile is a compatibility alias for the
same production configuration and now also requires the standard datasource
variables. Never combine `local`/`database` with `prod`/`render`.

## Required TLS encryption and manual Supabase setup

Use `sslmode=require` for the simplified university-project deployment. It requires
an encrypted PostgreSQL connection and fails if the server does not support TLS.
No separate CA file, Render Secret File, certificate mount or custom trust store is
required. This mode does **not** verify the server certificate or hostname.

1. Confirm the session-pooler hostname, port 5432, database name and username in
   Supabase's Connect dialog. The supplied pooler is in Canada Central and Render
   is in Ohio; cross-region latency has not been measured.
2. Set the three standard datasource variables above. Remove the `sslrootcert`
   query option from the simplified URL. An existing `supabase-ca.crt` secret file
   is unused by this configuration and can be removed manually if no longer needed.
3. Confirm network restrictions permit Render's outbound addresses and review
   database permissions. The supplied `postgres.*` identity may have broad access;
   prefer an appropriate least-privilege application role for future data APIs.
   JDBC does not inherit the browser user's Supabase JWT/RLS identity.
4. Verify actual database connectivity using explicitly authorized credentials.
   No live database has been contacted by this preparation.

Production validation accepts explicit `sslmode=require` and retains the existing
optional `sslmode=verify-full` support. For verify-full, add
`&sslrootcert=/etc/secrets/supabase-ca.crt` and supply the endpoint's trusted PEM CA
chain at that readable absolute path. Only this optional mode needs the CA file;
it verifies both certificate chain and hostname through pgJDBC's default factory.

Missing/blank credentials, absent TLS mode, `disable`, `allow`, `prefer`, unsupported
modes, duplicate options, embedded URL credentials, custom factories and custom
hostname verifiers remain rejected. Only `sslmode` and `sslrootcert` query options
are supported. Validation errors omit the URL/parser cause to protect credentials.
The datasource's security settings and bounded pool are explicitly constructed;
arbitrary `SPRING_DATASOURCE_HIKARI_*` values do not override them.

Database connections remain lazy: startup and `/api/health` do not prove live
connectivity, successful TLS negotiation, credentials or permissions. No migrations,
JPA entities or automatic schema initialization exist; `spring.sql.init.mode=never`
is retained. Do not add schema initialization overrides.

References: [Supabase connection methods](https://supabase.com/docs/guides/database/connecting-to-postgres),
[pgJDBC TLS modes](https://jdbc.postgresql.org/documentation/ssl/).

## Health, CORS and frontend integration

Use public `GET /api/health`, returning `200 {"status":"UP"}`. Actuator and Spring
Security are not installed; no authentication is required for this process check.
No sensitive diagnostics or configuration endpoints are exposed.

CORS is disabled by default. When `SIP_CORS_ALLOWED_ORIGINS` is set, only those exact
origins are permitted for `/api/**`. HTTP and HTTPS origins are accepted to support
local testing; use HTTPS for Vercel. Methods are GET/POST/PUT/PATCH/DELETE/OPTIONS;
allowed request headers are Authorization, Content-Type and Accept. Cookie
credentials are disabled. Wildcards, paths, queries and userinfo are rejected.
CORS is not authentication or authorization.

The unchanged Vite development proxy sends `/api` to `http://127.0.0.1:8080` while
Vite runs on 5173. This same-origin browser flow does not require CORS. The current
frontend demo makes no API calls. `VITE_API_BASE_URL` is reserved for future API
integration: set it to the stable HTTPS backend URL at Vercel build time, or use
`/api` with an explicitly configured Vercel rewrite. Vite's dev proxy does not run
on Vercel, and a `VITE_API_BASE_URL` value alone will not wire the demo to the API.
Do not hardcode a temporary Render URL or deploy browser API calls to localhost.

Authentication/JWT validation, signup/login/profile endpoints, schema/FK verification
and frontend API integration remain separate tickets (D02/D07/D09 references in
specifications; `docs/development-decisions.md` is absent in this checkout).
There is no custom password authentication or application password persistence.
These are future feature requirements, not prerequisites for the health scaffold.

## Build and local Docker tests

From `backend/`:

```sh
./mvnw -B -ntp verify
# Only when formatting changes are needed:
./mvnw -B -ntp spotless:apply
```

From the repository root with Docker installed and running:

```sh
docker build -t sip-backend:local backend
docker run --rm --name sip-backend --memory=512m --cpus=1 \
  -e SPRING_PROFILES_ACTIVE=local -e SERVER_ADDRESS=0.0.0.0 \
  -e PORT=10000 -p 8080:10000 sip-backend:local
```

In another terminal:

```sh
curl --fail http://127.0.0.1:8080/api/health
docker stats --no-stream sip-backend
docker inspect --format '{{.Config.User}}' sip-backend
docker stop sip-backend
```

For the production profile with an explicitly authorized **development** database,
create ignored `backend/.env.prod.local` containing `SPRING_PROFILES_ACTIVE=prod`,
the three standard datasource variables and optionally CORS origins. Use the
correct development host/user with `sslmode=require`. Never put a real password on
a command line. No certificate mount is required:

```sh
docker run --rm --name sip-backend --memory=512m --cpus=1 \
  --env-file backend/.env.prod.local \
  -p 8080:8080 sip-backend:local
```

Adjust port mapping if setting `PORT`.
Health success alone does not exercise JDBC; separately verify an authorized real
connection before claiming database readiness.

## Memory and verification limits

The container starts with 64 MB heap and caps heap at 256 MB, leaving the rest of
the 512 MB instance for metaspace, JIT code, thread stacks, native allocations and
OS overhead. Tomcat has 2 spare threads and at most 20 workers. The JDBC pool has
at most 3 connections, zero minimum idle connections, a 10-second acquisition
limit and a 10-second JDBC connect timeout. Out-of-memory errors exit the JVM.
These are initial bounds, not a load-tested capacity guarantee. Measure resident
memory and latency under actual workloads before changing them.

Docker is unavailable in the current environment. Image build, Linux/non-root
execution and enforcement of a real 512 MB cgroup limit remain unverified.
No live Supabase credentials are available locally, and database changes are not
authorized. Network reachability, role permissions and actual TLS negotiation
remain manual checks. CA trust is relevant only if selecting optional verify-full. See [Render Free limitations](https://render.com/docs/free)
for idle spin-down, cold starts and other hosting constraints.

## Historical verification on October 28, 2026

The results below predate the October 29 simplification to allow `sslmode=require`.

- `cd backend && ./mvnw -B -ntp spotless:apply verify`: successful compilation,
  executable JAR packaging, Java formatting and 29 tests (9 CORS configuration,
  3 HTTP health/CORS, 17 deployment configuration cases). The first sandboxed
  attempt could not bind the HTTP test port; the full rerun outside the sandbox
  passed. Tests used JDK 22.0.1 with compilation targeting Java 21.
- `python3 /tmp/sip6321-startup-check.py prod`: passed packaged JAR startup,
  environment-selected PORT, HTTP health, exact-origin CORS acceptance and
  rejection, using `-Xms64m -Xmx256m`. The temporary test helper supplied only
  fake credentials, `database.invalid`, and the local system public CA bundle.
  No real JDBC connection was opened. This is not a Linux/Docker memory test.
- `python3 /tmp/sip6321-startup-check.py local`: passed packaged JAR offline
  startup and health, and verified that default-disabled CORS returns no
  cross-origin permission headers. The temporary helper is a local audit artifact,
  not an application dependency; reusable Docker checks are documented above.
- `git diff --check` and `git diff --cached --check`: passed. Changed/untracked
  files were also checked for trailing whitespace and selected secret markers,
  with manual review of environment examples and configuration. No real credentials
  were added. Only environment templates are tracked. This is not a Git-history
  secret scan or a dependency vulnerability assessment.
- The Vite proxy was reviewed in source; the frontend was not modified or rebuilt.
  No browser-to-Vercel-to-Render integration test was performed because the demo
  does not yet issue API requests and no production frontend origin was supplied.
- Docker build/start and actual Supabase TLS/connectivity/permissions tests were
  not run: Docker and local database credentials are unavailable. No live database
  was modified, and nothing was committed, pushed or deployed.

Historical verdict (superseded by the October 29 update): **NOT READY for a verified
live Supabase deployment until the remaining
Docker and real database checks are completed.** The deployment configuration,
credential-free tests and native JAR startup checks pass. Authentication and
product functionality remain future tickets; this verdict covers the existing
health scaffold only. Existing README links to `docs/development-decisions.md`
and `docs/readiness-report.md` are broken because those documents are absent;
this does not prevent backend startup.

## Current verification on October 29, 2026

The university-project configuration now supports `sslmode=require` without any
CA file or secret-file mount. Optional certificate-verifying `verify-full` support
is preserved. TLS is still mandatory; plaintext-capable modes and missing explicit
TLS modes are rejected.

- `cd backend && ./mvnw -B -ntp spotless:apply verify`: **passed**, including
  compilation, executable JAR packaging, formatting and **33 tests**, with zero
  failures/errors. Tests cover require without a CA setting, optional verify-full,
  rejected plaintext modes, health, exact-origin CORS and profile compatibility.
- `python3 /tmp/sip6321-startup-require-check.py prod`: **passed**, using the packaged
  JAR, `sslmode=require`, fake credentials, `database.invalid`, no CA setting and
  a 256 MB maximum heap. HTTP health, PORT and CORS checks passed. The helper is a
  temporary local audit artifact; no live database connection was attempted.
- `git diff --check` and `git diff --cached --check`: **passed**. Deployment examples
  no longer require a CA file. Dockerfile, health endpoint, CORS configuration,
  prod/local profiles and memory/pool settings were preserved.

Verdict: **READY WITH WARNINGS for a Render deployment attempt.** Configure the
three standard datasource variables and retain `SPRING_PROFILES_ACTIVE=prod`.
No CA upload is required with `sslmode=require`. Actual Docker build/runtime,
512 MB container behavior and live Supabase TLS/connectivity/permissions remain
unverified locally because Docker and database credentials are unavailable.
`require` encrypts traffic but does not authenticate the database server identity.
Nothing has been committed, pushed, deployed or changed in the live database.
