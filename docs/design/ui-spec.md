# UI specification and prototype reference

## Sources and current status

**Local frontend demo update:** The user subsequently approved SOW branding, a landing page with a split photo background, centered login/sign-up forms, and a homepage with community posts on the left and opportunities on the right. This scope is implemented as a local presentation with simulated navigation/session state. See [demo instructions and boundaries](../../frontend/DEMO.md). The original source-backed responsibilities and unresolved product policies below remain distinct from that demo approval.

- [Detailed Design Document](../../Detailed%20Design%20Doc.pdf) (DDD), pp. 6–9 and 12–19: documented capabilities, component responsibilities, data fields and privacy requirements.
- [SIP6321 low-fidelity Figma prototype](https://www.figma.com/design/nkfx59VlujP8wPBWxXenmF/SIP6321-LowFidelity?node-id=0-1): supplied by the user as the project's prototype reference.

The Figma link has been supplied, but its frames and interactions could not be inspected through the available web reader. No screenshots, frame names, layouts, navigation or branding are claimed as verified from Figma. The requirements below come from the DDD; prototype-specific details remain open until the design is accessible. A supplied low-fidelity prototype does not by itself establish a final visual design or resolve backend policies.

## Frontend organization

The DDD specifies React + TypeScript and a presentation layer that depends on the Spring Boot REST API (pp. 7–9). The interface includes profiles, feed and events; backend services handle application responsibilities.

The frontend uses src/main.tsx as its React entry point and src/App.tsx as its root component. The local demo adds page components, shared presentation components, a stylesheet, and separate demo fixtures/session helpers. It uses browser history for its four local paths and makes no API requests. These are demo implementation choices, not production API or authentication requirements selected by the DDD.

## Interface responsibilities supported by the DDD

The following are capability responsibilities, not verified Figma screens or approved route names.

| Experience | Documented information and interactions | Source |
| --- | --- | --- |
| Authentication | Login is required for platform access; Supabase Authentication manages login and password security | pp. 7, 19 |
| Volunteer profile | Names, bio, avatar reference and standardized interests; the application supports volunteer profiles | pp. 6, 9, 12–13 |
| Organization profile | Organization name, description, website, verification flag, interest categories and memberships | pp. 6, 9, 13–14 |
| Opportunity discovery | Browse, search, filter and RSVP to volunteering opportunities; personalized opportunities use interests/preferences | pp. 6, 9 |
| Events and RSVP | Events contain organization, title, description, optional image, start/end times and location; registrations link users to events | pp. 16–17 |
| Social feed | Text/image posts, LIKE and SAVE interactions, text-only comments; saved posts are private account information | pp. 7, 15–16 |
| Volunteer hours | Manual submissions include volunteer, organization, hours and optional description; verification uses PENDING, APPROVED and DENIED | p. 17 |
| Messaging | Basic direct messages contain sender/recipient and text/image content | p. 18 |
| Administrative oversight | Distinct administrative permissions and monitoring of user posts/comments | pp. 6–7, 12 |

The presence of a database field does not approve displaying it to every user. Public profile fields, membership visibility and exact message-access rules require the privacy decisions described in [security.md](../security.md) and D02/D08 in [development decisions](../development-decisions.md).

## Forms and content constraints

Forms must respect the documented data types, required fields and permitted values in [data-model.md](../data-model.md). Posts and messages have an application-enforced text/image content requirement (pp. 15, 18). Comments are text-only (p. 16). JPEG/PNG images are supported and uploads undergo type/size validation (p. 19).

The DDD does not define field ordering, form labels, text limits for TEXT fields, validation messages, maximum upload size, positive-hour validation or event time-order validation. Those details must be confirmed rather than inferred from a prototype or database default.

## Details awaiting prototype inspection or team definition

| Topic | Current evidence / remaining decision |
| --- | --- |
| Screens and navigation | Figma reference supplied; frame inventory, links, role-specific navigation and URL routes not inspected or specified in the DDD |
| Layout and visual design | Figma reference supplied; colors, typography, spacing and final branding not verified |
| Responsive behavior | Supported viewport sizes, breakpoints and mobile navigation not specified in the DDD or verified from Figma |
| Loading, error and empty states | Exact presentation, retry and submission feedback not specified |
| Accessibility | Keyboard behavior, focus, labels and validation announcements need explicit specification; they are not claimed as prototype observations |
| Authentication journeys | Registration, recovery, email verification and organization onboarding remain undefined |
| Discovery and feed | Filter controls, ordering, pagination, recommendation presentation and existing interaction state remain undefined |
| Hours and management | Review authority, reversals, totals and volunteer-work-date/evidence scope remain undefined |

Following and sharing are named in the DDD (p. 6), but their behavior/persistence is not defined. Cancellation, organization leave, deletion-based moderation and message read indicators are also not established by the DDD. They are not adopted here as approved UI requirements.

See [user-flows.md](user-flows.md) for DDD-backed journeys and their unresolved steps. D05 remains open for prototype inspection; the blanket proposed screen/navigation design has been replaced with source-backed responsibilities and explicit gaps.
