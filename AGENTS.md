# SIP6321 coding-agent instructions

## Read before changing code

Authoritative specifications: [architecture](docs/architecture.md), [data model](docs/data-model.md), [API](docs/api.md), [security](docs/security.md), [UI](docs/design/ui-spec.md), [user flows](docs/design/user-flows.md), and [decisions](docs/development-decisions.md). The original Detailed Design Doc.pdf is the approved design source. Proposed/open details are not approved requirements.

## Constraints

- Preserve React/TypeScript, Java/Spring Boot, PostgreSQL/Supabase Auth/Storage and the four layers/five logical service areas. Keep business logic in backend services; avoid unnecessary frameworks or microservices.
- Review database structure changes before implementation; never invent fields/defaults/constraints from incomplete prose. No privileged credentials or secrets in React or VITE_* values.
- Preserve existing work. Inspect Git status; do not automatically commit, push, merge, deploy, or contact production databases.
- The readiness scaffold covers Sections 1–3 only. Do not select a first implementation task, write its agent plan, create full-schema migrations or implement product features without a subsequent task authorizing that work.

## Nontrivial workflow

Read relevant specs, inspect existing code, identify affected components, propose a bounded plan and necessary tests, and flag unresolved requirements with decision-register references. If the task explicitly requires plan-first development (including Section 4), stop after the plan for team approval. Otherwise follow authorization already given in the task. Record material uncertainties and request clarification when they block implementation; do not choose major product/architecture decisions for the team.

## Verification

- Frontend (`cd frontend`): `npm ci`, `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm test`, `npm run build`.
- Backend (`cd backend`): `./mvnw verify` compiles, runs tests and checks Java formatting; `./mvnw spotless:apply` formats. Windows uses `mvnw.cmd`.
- Review local documentation links and configuration templates manually. Run `git diff --check` for unstaged tracked changes and `git diff --cached --check` for staged changes; inspect untracked files separately and review changed files for secrets.
- Default backend `local` profile is database-free. Use the opt-in `database` profile only with explicitly authorized development credentials. Never require live Supabase for basic tests/builds. No automatic schema initialization.
- Use appropriate targeted tests, then required checks; report actual results and limitations. CI is in `.github/workflows/verify.yml`; setup is in README.md.
