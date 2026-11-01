# Social Impact Platform — SIP6321

Georgia Tech DCB6321 Senior Design project for client Tracy Dansby. SIP connects college students and young adults in Atlanta with nonprofits and volunteer opportunities, combining discovery with a volunteering community.

This repository prepares Development Readiness Sections 1–3 and includes an authorized **local frontend client demo** under the SOW brand: a landing page, simulated login/sign-up, and a sample community feed with opportunities. See [frontend demo instructions](frontend/DEMO.md). The backend now includes an internal [account persistence layer and deployment record](backend/ACCOUNTS.md). The project owner successfully applied V001 in Supabase; `public.users` has nine expected columns, its Supabase Auth UUID foreign key, and RLS enabled by the migration. Local backend verification passed all 70 tests, including 10 isolated PostgreSQL integration tests. Real authentication and account endpoints are not implemented. Section 4 belongs to another teammate.

## Approved stack and repository

React + TypeScript; Java + Spring Boot REST/JSON; PostgreSQL on Supabase; Supabase Auth and Storage; Vercel frontend hosting. The project owner reports an existing Render backend deployment. See the [backend configuration template](backend/.env.example) and [account deployment status](backend/ACCOUNTS.md#schema-and-deployment-status). The coding agent has not deployed the account persistence Java change or connected to the live database.

```text
docs/                         Architecture, data, API, security and decision specifications
  design/                     UI responsibilities and user flows
  readiness-report.md         Verification, file inventory and rubric assessment
frontend/                     Vite/React/TypeScript starter, Vitest, ESLint, Prettier
backend/                      Spring Boot starter, Maven wrapper, JDBC config, JUnit, Spotless
.github/workflows/verify.yml  CI checks, no deployment or secrets
AGENTS.md                     Persistent coding-agent workflow
Detailed Design Doc.pdf       Approved design source (19 pages)
Development Readiness Preparation.pdf  Assignment and rubric (6 pages)
```

## Prerequisites

- Node.js 24 and npm (use `.nvmrc` with `nvm use` if installed).
- JDK 21 recommended to match CI; Java compilation targets release 21. Previous local verification used JDK 22.
- Network access to npm and Maven Central for initial dependency downloads. Maven 3.9.16 is pinned by the wrapper; no system Maven install is required. Use `mvnw.cmd` on Windows.

## Run the frontend

Start from the repository root in a terminal:

```sh
cd frontend
npm ci
npm run dev
```

The starter does not need an environment file. For future approved integration work, copy `frontend/.env.example` to `frontend/.env.local` and replace the placeholders with development configuration.

Open `http://127.0.0.1:5173`. The frontend demo runs independently of the backend with local assets and sample content. Use made-up details in its login/sign-up forms. Vite proxies `/api` to the local backend at `http://127.0.0.1:8080`; optional exported `DEV_API_TARGET` changes the proxy destination. `VITE_API_BASE_URL=/api` is reserved for future API code; the demo makes no API requests. `npm run preview` serves the production build locally after `npm run build`.

## Run the backend

Use a separate terminal starting from the repository root:

```sh
cd backend
./mvnw spring-boot:run
```

The default `local` profile binds to loopback port 8080 and disables database auto-configuration. Verify from another terminal:

```sh
curl http://127.0.0.1:8080/api/health
```

Expected: `{"status":"UP"}`. This endpoint checks application availability only. To run the packaged backend after verification: `java -jar backend/target/sip-backend-0.0.1-SNAPSHOT.jar` from the repository root.

## Environment and development database

Copy templates only to ignored local files. Spring Boot **does not automatically read `.env` files**: configure exported variables or your IDE run environment. Vite loads `frontend/.env.local`; VITE_* values are public and frozen into the build. Supabase public URL/publishable-key examples are placeholders for future approved Auth work and are unused now. Never put a Supabase secret/service-role key or database password in VITE_*.

| Variable | Consumer | Meaning |
| --- | --- | --- |
| VITE_API_BASE_URL | Future frontend API adapter | `/api` locally; approved hosted API URL later |
| VITE_SUPABASE_URL / VITE_SUPABASE_PUBLISHABLE_KEY | Future frontend Auth integration | Public development project configuration |
| SPRING_PROFILES_ACTIVE | Spring | `local` (offline default), explicit `database`, or Docker's `prod` default (`render` is a compatibility alias) |
| SERVER_ADDRESS / SERVER_PORT | Spring | Local defaults 127.0.0.1 / 8080; `PORT` takes precedence over SERVER_PORT |
| SIP_DB_URL | Spring database profile | Development-only JDBC PostgreSQL URL with TLS |
| SIP_DB_USERNAME / SIP_DB_PASSWORD | Spring database profile | Development database credentials |
| SUPABASE_URL | Future backend Auth integration | Project URL; presently unused |
| SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD | Backend prod profile | Standard Render database settings; see the [configuration template](backend/.env.example) for required TLS encryption and [account deployment notes](backend/ACCOUNTS.md#manual-migration-procedure) for database access requirements |
| SIP_CORS_ALLOWED_ORIGINS | Backend | Optional comma-separated exact frontend origins for `/api/**`; disabled by default |
| DEV_API_TARGET | Vite config process | Optional dev proxy destination; export before starting Vite |

Database access is opt-in. Obtain a separate **development** Supabase connection string from the team's project settings, choose direct vs session-pooler connectivity for the network, and supply its host, port, username and password. Do not guess the username or use production. Example configuration (replace placeholders in your private shell/IDE configuration):

```sh
export SPRING_PROFILES_ACTIVE=database
export SIP_DB_URL='jdbc:postgresql://YOUR_DEV_HOST:5432/postgres?sslmode=verify-full'
export SIP_DB_USERNAME='YOUR_DEV_DATABASE_USER'
export SIP_DB_PASSWORD='YOUR_DEV_DATABASE_PASSWORD'
```

Then run the backend. `verify-full` verifies certificates/hostname and may require the provider's CA certificate/driver trust setup. Schema initialization is disabled (`spring.sql.init.mode=never`, `spring.jpa.hibernate.ddl-auto=none`). The account entity and manually applied migration are described in [account persistence](backend/ACCOUNTS.md). Starting the backend does not prove connectivity or schema readiness because database access initializes lazily. The project owner reports successful deployed connectivity; no live connection is attempted by the account work. Basic builds/tests require no Supabase account or environment files.

## Verify changes

Frontend, from `frontend/`:

```sh
npm ci
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build
```

`npm run test:watch` supports development; `npm run format` applies Prettier.

Backend, from `backend/`:

```sh
./mvnw verify
```

This compiles, runs the real HTTP health smoke test without database credentials, checks Java formatting and builds the executable JAR. Use `./mvnw spotless:apply` to format and `./mvnw test` for tests alone. Windows: substitute `mvnw.cmd`.

With Docker running, `./mvnw -Ppostgres-it verify` additionally runs the 10 isolated PostgreSQL
integration tests. Local verification on October 30, 2026 passed all 70 backend tests with no
failures, errors, or skips. These tests use a disposable container, never live Supabase. See
[account verification details](backend/ACCOUNTS.md#verified-results).

Repository root:

```sh
git diff --check
```

Review changed documentation links, configuration templates and files for accidental credentials manually. `git diff --check` checks unstaged tracked changes for whitespace problems; it does not inspect untracked files. Use `git diff --cached --check` when reviewing staged changes.

GitHub Actions is configured to run the frontend and backend checks on pushes and pull requests without production credentials. Hosted CI has not been executed from this local session.

## Specifications and limitations

- [Architecture](docs/architecture.md)
- [Complete data model](docs/data-model.md)
- [Database relationship diagram](docs/data-model.md#relationship-diagram)
- [DDD API and interface requirements](docs/api.md)
- [Security and privacy](docs/security.md)
- [UI specification](docs/design/ui-spec.md)
- [SIP6321 low-fidelity Figma prototype](https://www.figma.com/design/nkfx59VlujP8wPBWxXenmF/SIP6321-LowFidelity?node-id=0-1)
- [User flows](docs/design/user-flows.md)
- [Decisions and unresolved questions](docs/development-decisions.md)
- [Agent instructions](AGENTS.md)
- [Readiness report](docs/readiness-report.md)

The low-fidelity Figma prototype is linked above, but its frames/interactions have not yet been inspected. No iteration plan, detailed requirements archive or endpoint contracts were supplied. API contracts and unspecified UI behavior still need definition; security proposals need review. Correct the original design's plaintext-password statement, clarify organization/admin roles and incomplete database constraints, and approve the initial scope before feature implementation. See the decision register and readiness report for evidence and Go/No-Go risks. Team review and understanding, Section 4, credentials and deployment remain separate responsibilities.
