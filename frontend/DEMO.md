# SOW frontend: authentication and content previews

Use Node 24. From `frontend/`, run `npm ci`, then `npm run dev` and open
<http://127.0.0.1:5173>. Local images, fonts, and icons remain bundled.

## Development setup

Copy `.env.example` to `.env.local` (ignored by Git) and set the development Supabase
project URL and publishable key used by the backend. These are public browser settings.
Never copy backend database credentials or service-role keys into frontend configuration.
Restart Vite after editing settings.

Run the backend with Java 21, the database profile, Auth enabled, and authorized development
settings as described in [backend signup setup](../backend/SIGNUP.md). Its existing users
migration must already be applied. Vite proxies `/api` to `http://127.0.0.1:8080`;
exported `DEV_API_TARGET` can change that destination. For a deployed API, set
`VITE_API_BASE_URL` to its full API prefix and configure backend exact-origin CORS.
`npm run preview` does not provide the Vite development proxy.

Without service configuration you can view the landing/account layouts, but authentication
will not simulate success. Automated tests use mocks and need no environment file.

## Account walkthrough

1. Choose **Join SOW** or **Log in**. Signup sends email, password, first name, and last
   name to Spring; all new accounts are STANDARD. Use a fresh development test account
   for signup. It creates a real account, and the current backend contract requires
   email confirmation to be disabled in that development project.
2. Spring verifies the Supabase identity and persists/reads the application account.
   The frontend adopts the returned session through the Supabase browser client and
   fetches `/api/users/me` before showing the homepage with the profile's first name.
3. Sessions persist across refreshes through [Supabase's standard localStorage session](https://supabase.com/docs/reference/javascript/auth)
   lifecycle. The client handles refresh while the app is open and restores sessions
   on reload. Tokens are accessible to JavaScript; never log or share browser storage.
   Passwords are never stored in the application's browser storage.
4. Invalid credentials stay on the form with a safe error. Profile outages offer retry;
   missing profiles direct users to support. Invalid/expired sessions return to login.
   Failed signup is never automatically retried, because the account may already exist.
5. **Log out** clears the current browser's private UI and requests current-session
   [sign-out with Supabase](https://supabase.com/docs/reference/javascript/auth-signout). If remote sign-out fails, this browser still forgets its
   session and reports the failure. Existing access tokens may remain valid until expiry.
   Other devices are not signed out.

## Content boundary and verification

Posts, organizations, categories, schedules, and opportunities remain sample fixtures in
`src/demo/content.ts`. Likes/saves are temporary UI state; posting, commenting, and event
registration remain previews. No feed, RSVP, role management, or profile-edit API is added.
The legacy `sow-demo-session` value no longer grants access to the homepage.

Run `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm test`, and
`npm run build`. Tests cover API contracts, session restoration, protected navigation,
safe failures, stale responses, refresh events, logout, and content previews. Live local verification is recorded below.

## Verification — November 5, 2026

All required frontend checks passed: `npm ci`, typecheck, lint, format check, 31 tests,
and production build. Backend `./mvnw verify` passed 261 tests, formatting, and packaging.
App tests explicitly isolate the API base so private `.env` values cannot affect them.

With explicit user authorization, one development test account was created through the
current local Spring signup endpoint (HTTP 201 with application account and session).
Browser login loaded its real profile; reload preserved sign-in; logout returned to the
landing page; a direct home visit after logout redirected to login. A wrong password
was rejected, and a subsequent correct login succeeded. Repeating signup for this same
account returned HTTP 409 / SIGNUP_CONFLICT, and a profile read without a token returned
HTTP 401 / UNAUTHORIZED. The account remains in the development project; no automatic
delete or reconciliation was performed. No personal account credentials or tokens are
recorded here. Automatic token refresh is covered by mocked tests, not a timed live test.

The user's private frontend setting targets Render. Render health/preflight/empty-login
probes timed out in this environment, so its availability and CORS are unverified. Browser
smoke testing used a process-only `VITE_API_BASE_URL=/api` override to reach the current
local backend; the private Render setting was preserved. A normal restart without that
override uses the configured Render URL. The local backend fixes still need normal team
review and deployment before they can affect Render. No deployment was performed.
