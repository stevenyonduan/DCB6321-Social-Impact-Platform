# Implementation Plan: Authentication UI and Account Access Flow

## Purpose and authority

This plan is limited to the front-end authentication pages and the adjacent API integration required to support login and sign-up flows in the current project. It is intentionally bounded to the approved guidance in the project documents and does not authorize broader product features or schema changes beyond the approved readiness scope.

Relevant sources that govern this work:

- [docs/architecture.md](../architecture.md)
- [docs/data-model.md](../data-model.md)
- [docs/api.md](../api.md)
- [docs/security.md](../security.md)
- [docs/design/ui-spec.md](../design/ui-spec.md)
- [docs/design/user-flows.md](../design/user-flows.md)
- [docs/development-decisions.md](../development-decisions.md)

The project’s design records and AGENTS instructions specify the following legal constraints:

- Supabase Auth manages login, password security, and sessions.
- No plaintext password storage is permitted in application code or database tables.
- `users.id` must match the Supabase `auth.users.id`.
- Role values remain `STANDARD`, `ADMIN`, and `ORGANIZATION`.
- Business logic must remain in backend services; the React layer should not enforce privileged authorization decisions.
- The readiness scaffold covers Sections 1–3 only; this plan does not authorize new product features or a full database migration unless a later task explicitly approves that work.

## In-scope work

This task covers the following bounded implementation work:

### Frontend: authentication screens and routing

- Add an authentication entry screen that includes both sign-up and login flows.
- Implement a sign-up form with fields needed for account creation and a role selector representing the approved account types: Standard, Admin, and Organization.
- Implement a login form with email and password inputs.
- Add a protected profile entry point that redirects unauthenticated users to the sign-in/sign-up screen.
- Add loading, success, and error states for all authentication-related requests.
- Keep UI validation focused on form completeness and API feedback; do not add custom privilege enforcement in React.

### Frontend-to-backend contract

- Create or update the client API layer to call the backend authentication endpoints that are already approved or are being introduced as part of this bounded task.
- Ensure the client sends only the fields the backend expects and never duplicates or invents password or authorization data.
- Treat role values as user input for the sign-up form, but rely on backend validation and business rules before accepting or storing a role.

### Backend: authentication boundary and account data access

- Confirm the backend uses the project’s shared authentication infrastructure and resolves the authenticated user identity from the Supabase token.
- Expose the minimum required endpoints for sign-up and login behavior in the existing Spring Boot API structure.
- Ensure the backend validates credentials and role requests according to the approved application rules, without introducing a second password store.
- Provide a protected profile endpoint that returns only the authorized user data for an authenticated identity.
- Keep all authorization checks and business rules server-side.

### Data and configuration constraints

- Do not invent nullable/default/constraint rules beyond the approved schema definitions in the design docs.
- Do not implement automatic database schema initialization or live Supabase dependency in normal build/test paths.
- Keep database integration opt-in and credential-free for default local builds/tests.

## Explicitly out of scope

The following items are not approved as part of this task and must not be implemented under this plan unless a subsequent task and approval are added:

- Full database migration or schema creation beyond the minimum agreed auth/profile contract required by the system.
- Automated deletion jobs for unverified users or privileges.
- Organization verification workflows or admin-role assignment logic beyond the existing approved schema and identity model.
- A final authorization matrix for all ADMIN/ORGANIZATION actions across the platform.
- Any feature that assumes the frontend alone decides access control, role assignment, or profile visibility.

## Known unresolved design questions

The design docs explicitly flag certain details as not resolved, and this implementation plan intentionally does not treat them as approved requirements:

- The exact registration and email-verification flow.
- The exact organization onboarding and role-verification process for `ADMIN` and `ORGANIZATION` accounts.
- The full permission matrix for organization management and hour verification.
- The exact profile fields that are public versus private.
- The exact policy for session lifetime, refresh behavior, and client-side storage.

These items must be tracked as open design questions, not implemented as assumptions in this task.

## Implementation steps

### 1. Confirm the existing code paths and contracts

- Read the current frontend routes, app shell, and API client structure.
- Review the current backend controller/service layout for authentication-related endpoints.
- Confirm whether the groundwork already exists for profile access and auth integration before editing implementation files.

### 2. Implement the auth UI shell

- Add an authentication page that supports sign-up and login tabs or sections.
- Add route protection for the profile-related navigation.
- Provide a consistent UX for loading, validation, errors, and success messages.

### 3. Implement the sign-up and login form logic

- Collect the fields required by the approved contract.
- Submit requests through the client API layer using the project’s normal HTTP patterns.
- Surface API errors without exposing sensitive details.
- Keep all role and access decisions server-owned.

### 4. Implement the backend contract needed for the UI

- Add only the endpoints necessary to support the approved front-end login/signup flow.
- Validate credentials and request payloads before returning data.
- Ensure profile retrieval checks the authenticated identity and returns only permitted data.
- Keep the implementation aligned with Supabase Auth and the existing backend architecture.

### 5. Validate the implementation within the project constraints

- Run the required frontend verification commands if the project is modified in this area.
- Run the required backend verification commands if backend code is touched.
- Confirm no plaintext passwords are introduced and that no live Supabase credentials are required for standard builds/tests.

## Acceptance criteria for this task

This task is considered complete only when the following are true:

- A user can access the sign-up and login UI from the project’s frontend.
- The user can submit sign-up and login requests using the existing API layer without invalid client-side authorization assumptions.
- Unauthenticated users can be redirected to the authentication flow before accessing a profile-related route.
- The application handles loading, validation, and API error states without exposing secrets or bypassing backend authorization.
- The implementation respects the approved design constraints and does not assume unresolved verification or authorization rules.

## Verification checklist

Before marking this task complete, verify:

- The implementation is grounded in the authoritative design docs.
- Supabase Auth remains the password/session authority.
- No plaintext password handling was introduced.
- No unauthorized schema assumptions or migration logic were added.
- The task remains bounded to UI and auth flow support rather than broad product authorization features.
- Frontend and backend verification commands are run according to the project’s AGENTS guidance when applicable.
