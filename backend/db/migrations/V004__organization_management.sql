-- Manual, additive migration after the already-applied V001/V002/V003. Never rerun those files.
-- Run db/preflight/organization_management.sql first. No automatic legacy ownership inference.
BEGIN;

-- Block concurrent legacy writes while validating and applying the approved mapping.
LOCK TABLE public.organizations, public.users IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE public.users ADD COLUMN organization_role VARCHAR(20);

-- REVIEW GATE: leave empty for a database without organizations/associations.
-- For legacy data, insert ONLY team-approved UUID mappings here before manual execution.
-- Every associated user and every organization's exactly one OWNER must be accounted for.
-- Unassociated existing users may be explicitly selected for an ownerless legacy organization.
CREATE TEMP TABLE approved_organization_roles (
    user_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    organization_role VARCHAR(20) NOT NULL CHECK (organization_role IN ('OWNER', 'ADMIN'))
) ON COMMIT DROP;
-- INSERT INTO approved_organization_roles VALUES
--     ('APPROVED_USER_UUID', 'EXISTING_ORGANIZATION_UUID', 'OWNER');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM approved_organization_roles m
        LEFT JOIN public.users u ON u.id = m.user_id
        LEFT JOIN public.organizations o ON o.id = m.organization_id
        WHERE u.id IS NULL OR o.id IS NULL
           OR (u.organization_id IS NOT NULL AND u.organization_id <> m.organization_id)
    ) THEN
        RAISE EXCEPTION 'V004: invalid approved mapping; review legacy reconciliation';
    END IF;
END $$;

UPDATE public.users u
SET organization_id = m.organization_id, organization_role = m.organization_role
FROM approved_organization_roles m WHERE u.id = m.user_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.users WHERE organization_id IS NOT NULL AND organization_role IS NULL)
       OR EXISTS (
           SELECT 1 FROM public.organizations o
           LEFT JOIN public.users u ON u.organization_id = o.id AND u.organization_role = 'OWNER'
           GROUP BY o.id HAVING count(u.id) <> 1
       ) THEN
        RAISE EXCEPTION 'V004: explicit OWNER/ADMIN reconciliation required; transaction must be rolled back';
    END IF;
END $$;

ALTER TABLE public.users ADD CONSTRAINT users_organization_role_check
    CHECK (organization_role IS NULL OR organization_role IN ('OWNER', 'ADMIN'));
ALTER TABLE public.users ADD CONSTRAINT users_organization_association_check CHECK (
    (organization_id IS NULL AND organization_role IS NULL)
    OR (organization_id IS NOT NULL AND organization_role IS NOT NULL)
);
CREATE UNIQUE INDEX users_one_organization_owner_idx ON public.users (organization_id)
    WHERE organization_role = 'OWNER';
CREATE INDEX users_organization_id_idx ON public.users (organization_id);

-- Transactional, backend-only audit of successful changes. No email/token/profile snapshots.
CREATE TABLE public.organization_management_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    target_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    organization_id UUID NOT NULL REFERENCES public.organizations(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    action VARCHAR(20) NOT NULL CHECK (action IN ('OWNER_CREATED', 'ADMIN_ADDED', 'ADMIN_REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX organization_management_audit_organization_time_idx
    ON public.organization_management_audit (organization_id, created_at, id);
ALTER TABLE public.organization_management_audit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.organization_management_audit FROM PUBLIC, anon, authenticated;
-- Existing FKs, global roles, event records, RLS and browser revocations are preserved.
-- Review effective backend users UPDATE, organizations INSERT, audit INSERT and RLS access.
COMMIT;
