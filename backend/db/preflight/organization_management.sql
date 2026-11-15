-- READ ONLY. Run manually with an authorized operator role; results include private account emails.
BEGIN TRANSACTION READ ONLY;
-- Existing associations (all global roles): each requires an explicit approved mapping in V004.
SELECT u.id, u.email, u.role, u.organization_id, o.name
FROM public.users u LEFT JOIN public.organizations o ON o.id = u.organization_id
WHERE u.organization_id IS NOT NULL ORDER BY u.organization_id, u.id;
-- Global legacy ORGANIZATION accounts, including unassociated accounts.
SELECT id, email, role, organization_id FROM public.users
WHERE role = 'ORGANIZATION' ORDER BY id;
-- Organizations with no associated users: choose a registered OWNER explicitly; never delete them.
SELECT o.id, o.name FROM public.organizations o LEFT JOIN public.users u ON u.organization_id = o.id
GROUP BY o.id, o.name HAVING count(u.id) = 0 ORDER BY o.id;
-- Multiple associations: explicitly select exactly one OWNER and classify the remaining ADMINs.
SELECT o.id, o.name, count(u.id) AS associated_users
FROM public.organizations o JOIN public.users u ON u.organization_id = o.id
GROUP BY o.id, o.name HAVING count(u.id) > 1 ORDER BY o.id;
-- Legacy inconsistencies for review; STANDARD/ADMIN associations must not be silently discarded.
SELECT u.id, u.email, u.role, u.organization_id,
    CASE WHEN u.organization_id IS NOT NULL AND o.id IS NULL THEN 'DANGLING_ORGANIZATION'
         WHEN u.role = 'ORGANIZATION' AND u.organization_id IS NULL THEN 'UNASSOCIATED_LEGACY_ROLE'
         ELSE 'ASSOCIATED_NON_ORGANIZATION_GLOBAL_ROLE' END AS review_reason
FROM public.users u LEFT JOIN public.organizations o ON o.id = u.organization_id
WHERE (u.organization_id IS NOT NULL AND o.id IS NULL)
   OR (u.role = 'ORGANIZATION' AND u.organization_id IS NULL)
   OR (u.role <> 'ORGANIZATION' AND u.organization_id IS NOT NULL)
ORDER BY u.id;
COMMIT;
