# Architecture

Authority: [Detailed Design Document](../Detailed%20Design%20Doc.pdf), pp. 5–9, Figure 2 (p. 8), and p. 19. The original static diagram is authoritative. This document transcribes its design; unresolved implementation choices are tracked in [development decisions](development-decisions.md).

## Purpose and scope

SIP6321 connects college students and young adults in Atlanta with nonprofits and volunteer opportunities for client Tracy Dansby. It centralizes discovery and supports a volunteering community. Approved system capabilities include profiles and interests, opportunity search/filtering and RSVP, posts and interactions, recommendations, hours and verification, administrative moderation, and direct messages. These are system scope, not a confirmed MVP or iteration commitment. Following/sharing are mentioned but lack a persistence or behavior contract (D04).

## Stack and responsibilities

| Component | Approved technology | Rationale and responsibility |
| --- | --- | --- |
| Presentation | React + TypeScript | Team familiarity, reusable components and typed interfaces |
| API and business logic | Java + Spring Boot | Team Java experience; REST endpoints and organized business logic |
| Persistence | PostgreSQL on Supabase | Relational entities and junction tables; consistency |
| Authentication | Supabase Auth | Managed identities, sessions and password security |
| Files | Supabase Storage | Images separated from relational records |
| Frontend hosting | Vercel | Simple frontend publication workflow |

## Four layers

1. **Presentation:** React renders profiles, feed, events and other approved views; collects inputs and displays API results. UI permission checks improve usability but cannot authorize operations.
2. **API:** Spring controllers accept HTTPS/JSON, verify identity, validate request shape, call services and map responses/errors. Avoid exposing persistence records directly.
3. **Application/service:** Account and Profile handles identities, profiles and interests; Opportunity and RSVP handles events and registrations; Social Feed handles posts, comments and interactions; Recommendation uses interests/preferences; Volunteer Hours and Verification manages submissions and review. Messaging and moderation must be assigned within this structure by the team (D08).
4. **Data/infrastructure:** PostgreSQL stores the 13 approved tables; Supabase Auth manages login; Storage stores images. Repositories/adapters encapsulate infrastructure access.

The [data model and original relationship diagram](data-model.md#relationship-diagram) document the PostgreSQL entities and relationships from DDD Figure 4.

## Integration boundaries

- React calls Spring Boot REST endpoints over HTTPS with JSON. [api.md](api.md) records the DDD interface requirements; endpoint contracts and token transport remain unspecified. The scaffold health endpoint is documented separately.
- Spring services apply authorization and business rules before using database repositories. The account persistence ticket adds Spring Data JPA and a manually applied, versioned users-table migration; see [account persistence](../backend/ACCOUNTS.md). Browser access must not bypass backend business logic.
- Supabase Auth owns authentication and passwords. A proposed integration uses its browser client for sessions and backend verification of access tokens before resolving `users.id`. Signup-to-profile synchronization, signing-key configuration and role provisioning need confirmation (D02, D07).
- Supabase Storage owns image bytes; approved tables store URL references. JPEG/PNG and type/size validation are approved. Bucket policy, maximum size, private URL strategy and upload route remain pending (D06).
- Production uses HTTPS. Local loopback HTTP is only a development convenience. Vercel deployment and backend deployment are not configured in this readiness task.

## Implementation constraints

Keep business rules in backend services, not React or controllers. Maintain transactional consistency for relationship writes and hours review. Never trust client-supplied roles, authors or verification identities. Use DTOs that expose only authorized fields. Preserve UUIDs, composite keys and the approved tables; resolve schema gaps before migrations. Do not introduce frameworks, platforms, service splitting, realtime transports or algorithms without a documented need and review. No business features or schema migrations are part of this scaffold.
