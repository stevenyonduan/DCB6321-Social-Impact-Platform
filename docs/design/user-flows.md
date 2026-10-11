# User flows — source-backed behavior

Sources: [Detailed Design Document](../../Detailed%20Design%20Doc.pdf) (DDD), pp. 6–9 and 12–19, and the user-supplied [SIP6321 low-fidelity Figma prototype](https://www.figma.com/design/nkfx59VlujP8wPBWxXenmF/SIP6321-LowFidelity?node-id=0-1).

The prototype's frames and interactions have not been inspected because the web reader could not access the file. The journeys below summarize DDD behavior, not observed Figma click paths. Exact screens, ordered onboarding steps and transitions remain unspecified where the DDD does not define them. No first implementation task or iteration priority is selected.

## Documented journeys and limits

| Journey | User action and system behavior established by the DDD | Access requirements / unresolved details | Source |
| --- | --- | --- | --- |
| Registration and login | Users log in to access the platform; Supabase Authentication manages login/password security | Registration sequence, profile synchronization, email verification, recovery and post-login destination are not specified | pp. 7, 12, 19 |
| Volunteer profile | Application supports volunteer profile information and interests; profile data and selected interests are stored in users/user_interests | Setup/edit sequence and public field exposure are not specified | pp. 6, 9, 12–13 |
| Organization account | Application supports nonprofit profiles/interests; organizations and account associations are represented in the schema | ADMIN/ORGANIZATION accounts are described as tied to one organization; onboarding, assignment and exact management rights need clarification | pp. 6, 9, 12–14 |
| Opportunity discovery | Users browse, search and filter opportunities; recommendations personalize content using interests/preferences | Login required; filters, ordering, ranking, pagination and detail-screen transitions are not specified | pp. 6–7, 9 |
| Event RSVP | Users RSVP to events; event_registrations records the user/event association and registration time | Duplicate relationship prevented by composite key; cancellation, capacity and past-event behavior are not specified | pp. 6, 17 |
| Social posting and interaction | Community feed supports text/image posts, LIKE/SAVE interactions and text-only comments | Posts require text or image through application logic; saved posts are private. Posting/editing permissions and sharing/following behavior are not defined | pp. 6–7, 15–16 |
| Organization membership | Volunteers join organizations/groups; organization_members records the association and joined_at | Admission, leave/removal and whether any membership grants additional permissions are not specified | p. 14 |
| Volunteer-hour submission | Manual hours are submitted for a volunteer/organization; logged_hours stores hours, optional description and default PENDING status | Submission workflow, positive-hour rule, date of volunteer work, evidence and edits are not specified; submitted_at is already documented | p. 17 |
| Volunteer-hour verification | Submitted hours receive APPROVED or DENIED status; verified_by and updated_at record reviewer and approval/denial time | p. 17 describes admin verification; p. 19 allows authorized organization accounts to verify. Exact reviewer authority, self-review and reversals are not specified | pp. 17, 19 |
| Administrative moderation | Admins monitor user posts/comments with permissions distinct from other account types | Scope, removal/suspension actions, confirmation steps and audit policy are not specified | pp. 6–7, 12 |
| Direct messaging | Basic messages identify sender/recipient and carry text/image content; application logic requires text or image | Authentication/authorization protect messages. Exact access rules, inbox/history flow, transport and read indicators are not specified | pp. 18–19 |

## Authentication and privacy boundaries

The DDD requires login, role-dependent access, private account information such as saved posts, and authentication/authorization protection for profiles and messages (pp. 7, 19). Backend-sensitive actions must follow the access controls established for the approved scope. The DDD does not supply a complete role/resource matrix or state that ordinary membership grants organization-management authority.

The plaintext-password sentence on DDD p. 7 is superseded by the user's explicit security correction: Supabase Auth manages password security. No application plaintext-password storage is permitted.

## Remaining flow specification work

Prototype inspection is needed to document actual frame names, entry points, navigation controls and transitions. Team decisions are also needed for behaviors the DDD leaves unspecified, including registration/onboarding, cancellation/leave, moderation actions, hour-review rules and privacy.

Loading, error, empty-state, retry and session-expiry interactions are not defined by the DDD and have not been verified in Figma. The previous operational proposals are not treated as approved behavior here.

Refer to [UI specification](ui-spec.md), [DDD interface requirements](../api.md), [security](../security.md) and D02/D04–D11 in [development decisions](../development-decisions.md). The security document's separately labeled proposals still require review; linking them here does not approve them.
