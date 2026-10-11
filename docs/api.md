# API and interfaces — Detailed Design Document requirements

Source: [Detailed Design Document](../Detailed%20Design%20Doc.pdf) (DDD), pp. 5–9 and 12–19. This document records the interface requirements established by the DDD. The DDD does not define individual endpoint paths, HTTP methods, request/response schemas or error contracts; those details remain unspecified rather than being supplied as proposed contracts here.

## Communication and backend responsibilities

The DDD establishes the following:

| Requirement | Source |
| --- | --- |
| React + TypeScript provides the frontend; Java + Spring Boot provides the REST API and business logic | pp. 7–9, Figures 1–3 |
| The frontend sends requests to the backend through a RESTful API over HTTPS | p. 19, Data Exchange |
| Data is exchanged primarily in JSON format | p. 19, Data Exchange |
| The backend processes requests, applies application logic and interacts with Supabase PostgreSQL to retrieve, create, update or delete records | p. 19, Data Exchange |
| Supabase Authentication manages user login and password security | p. 19, Data Exchange |
| Access controls restrict sensitive operations according to roles and permissions, including organization event management and hour verification | pp. 7 and 19 |
| Uploaded image files are stored in Supabase Storage; database records contain references to those files | p. 19, File Use |

These requirements describe the system's interfaces and responsibilities. They do not establish an API base path, token header format, JSON property naming convention or a one-to-one mapping between database tables and API resources.

## Functional interface areas

The five logical backend service areas come from Figure 2 (p. 8) and the component responsibilities on p. 9. Organizations are covered by account/profile responsibilities. Messaging and administration are described in the DDD, but their placement within the five service areas is not specified.

| Area | Functionality described by the DDD | Related approved data | Source |
| --- | --- | --- | --- |
| Accounts and profiles | Volunteer and nonprofit accounts/profiles; profile information and interests | users, interests, user_interests | pp. 6, 9, 12–13 |
| Organizations | Nonprofit profiles, interests/categories and volunteer membership in organizations/groups | organizations, organization_interests, organization_members; users.organization_id | pp. 6, 9, 12–14 |
| Opportunities and RSVPs | Browse, search, filter and RSVP to volunteer opportunities; organizations publish opportunities | events, event_registrations | pp. 6, 9, 16–17 |
| Social feed | Community posts and social interactions, including text/image posts, likes, saves and text-only comments | posts, post_interactions, post_comments | pp. 6, 9, 15–16 |
| Recommendations | Personalized volunteer opportunities/content using interests and preferences | User and organization interest relationships | pp. 6, 9, 13–14 |
| Volunteer hours and verification | Manual volunteer-hour submissions and verification | logged_hours | pp. 6, 9, 17, 19 |
| Messaging | Basic direct messages with sender, recipient and text/image content | messages | p. 18 |
| Administration | Different administrative permissions and oversight of user posts/comments | users.role; posts, post_comments | pp. 6–7, 12 |

Following and sharing are named on p. 6, but the DDD does not define their API behavior or persistence structure. The documented post_interactions values are LIKE and SAVE (p. 15); following/sharing must not be represented as additional approved values. See D04 in [development decisions](development-decisions.md).

This is system scope, not a selected MVP feature list or implementation order.

## Data constraints relevant to interfaces

The approved schema constrains the data the application manages. It does not prescribe which fields are accepted or returned by each API operation. Full column definitions are in [data-model.md](data-model.md).

| Source-backed constraint | Interface implication | Source |
| --- | --- | --- |
| users.id matches Supabase auth.users.id; roles are STANDARD, ADMIN and ORGANIZATION | Account identity and role handling must be consistent with these records; provisioning and role-assignment mechanics are not specified | p. 12 |
| Junction tables use composite primary keys | Stored interest selections, memberships, registrations and interactions cannot duplicate the same key combination; duplicate-request behavior is not specified | pp. 10, 13–17 |
| Posts require either text or image content through application logic | Post handling must enforce the documented content requirement | p. 15 |
| post_interactions permits LIKE and SAVE | Interaction handling uses the documented values | p. 15 |
| Comments contain required text content | Comment handling respects the text-only design | p. 16 |
| Events belong to organizations and contain required title, description, start/end times and location | Opportunity handling respects the event schema; time-order validation and cancellation policies are not specified | p. 16 |
| logged_hours contains hours as NUMERIC(5,2), organization/volunteer references, and PENDING/APPROVED/DENIED status with default PENDING | Hour handling respects these fields, types and statuses; positive-only validation and review-transition rules are not specified | p. 17 |
| Messages require either text or image content through application logic | Messaging handling must enforce the documented content requirement | p. 18 |
| Uploaded images support JPEG/PNG and undergo type/size validation | File handling must support the approved formats and validation; upload size limits and interface details are not specified | p. 19 |

The database's UUID, VARCHAR, TIMESTAMPTZ and NUMERIC types are documented in the data model. Their exact JSON representations, field exposure and serialization rules are not defined by the DDD.

## Authentication, authorization and privacy

The DDD requires login to access the platform and different permissions for volunteers, admins and nonprofits (p. 7). It identifies saved posts as private account information and requires authentication/authorization protection for personal information, profiles and messages (pp. 7 and 19).

Authorized organization accounts can manage events and verify volunteer hours (p. 19). The event table refers to an admin creator (p. 16), and the hours table describes admin verification (p. 17). The precise ADMIN/ORGANIZATION permission matrix needs clarification; this document does not resolve that ambiguity or assign management rights to ordinary members.

The DDD does not specify bearer-token transport, JWT verification configuration, which profile fields are visible to other users, or an exact message-access policy. Refer to [security.md](security.md) for the security correction and separately labeled implementation proposals; those proposals are not endpoint contracts approved by the DDD.

The plaintext-password statement on p. 7 is superseded by the user's explicit security correction: Supabase Auth manages password security. No application plaintext-password storage is permitted.

## Interface details not specified in the DDD

The following remain open for team definition and review:

- Endpoint paths/base path, HTTP methods and operation-specific success status codes.
- Request and response fields, public/private field exposure, JSON naming, null handling and timestamp/decimal serialization.
- Error response structure and operation-specific validation/conflict behavior.
- Search/filter parameters, pagination, ordering and recommendation ranking.
- Account/profile provisioning, organization onboarding and role assignment.
- Upload operations, maximum file size, storage policies and private-file access.
- RSVP cancellation, membership admission/leave, editing/deletion and moderation workflows.
- Hour-review transitions and reviewer permissions; messaging transport and access rules.

These omissions are limitations of the supplied design, not approved defaults. D02 and D04–D11 in [development-decisions.md](development-decisions.md) track the related questions. The source-backed interface requirements above are documented, but endpoint-level implementation readiness requires these contracts to be defined for the team's approved scope.

## Existing development health endpoint

The current scaffold separately implements GET /api/health with no request body or authentication and a 200 JSON response of {"status":"UP"}. This was permitted by the readiness request; it is not a product endpoint specified in the DDD. It checks process availability, not database, Auth or Storage connectivity. No product endpoints are implemented by this readiness work.
