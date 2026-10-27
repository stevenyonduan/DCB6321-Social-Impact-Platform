# SOW local client demo

From `frontend/`, use Node 24, run `npm ci` once, then `npm run dev`. Open <http://127.0.0.1:5173>. No backend, Supabase project, credentials, or environment file is needed. After installation, the app's images, fonts, and icons work offline.

## Walkthrough

1. Start at `/` for the approved landing page. The background photo is anchored left; on narrow screens it appears below the text without cropping.
2. Choose **Join SOW** for `/signup`, or **Log in** for `/login`. Both use the same centered account layout. The SOW logo always links to the landing page.
3. Use made-up details (for example `alex@example.com` and any nonempty demo password). Sign-up also requires first and last names. Submitting opens `/home`.
4. The homepage shows community posts on the left and opportunities on the right. On narrow screens the opportunities follow the posts. Likes and saves are temporary UI state; posting, commenting, and event actions explain their preview status.
5. Refresh preserves the demo session in the same tab. **Log out** clears it and returns to the landing page. Visiting `/home` without a demo session redirects to `/login`. Browser back/forward and direct account URLs are supported by the Vite SPA fallback.

## Integration boundary

This is explicitly a frontend presentation, not real registration, authentication, authorization, or persistence. It sends no credentials and makes no API requests. Only a demo display name is kept in `sessionStorage` under `sow-demo-session`; no email, password, role, or token is stored. If browser storage is disabled, the current walkthrough works in memory and refresh returns to login.

Sample organizations, posts, categories, schedules, and opportunities live in `src/demo/content.ts`; they are not approved database seeds or API contracts. Demo session helpers live in `src/demo/session.ts`. Replace this boundary with the team's approved Supabase Auth integration later. The backend and database remain unchanged.

The user approved this scope and the final visual drafts in chat. The earlier authentication implementation plan describes broader future backend work and does not govern this demo's integration scope. Role provisioning (D02), real session/profile synchronization (D07), and privacy/authorization (D08/D09) remain unresolved; the referenced decision register is missing from this checkout. No backend decisions are implied by the preview.

Run `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm test`, and `npm run build` to verify. Tests cover navigation, demo session restoration/clearing, form validity, credential omission, and preview actions. Asset origins are documented in `src/assets/README.md`.
