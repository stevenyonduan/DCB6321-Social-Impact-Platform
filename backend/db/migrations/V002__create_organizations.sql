-- Apply manually once after V001; never rerun or edit the applied V001.
-- PostgreSQL 13+ provides gen_random_uuid() without an extension/search_path dependency.
-- Existing non-null users.organization_id values must already reference a real organization.
-- Because this table is new, dangling legacy associations abort the entire migration;
-- review/reconcile them separately rather than silently rewriting or deleting account data.
BEGIN;

CREATE TABLE public.organizations (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    website VARCHAR(255),
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT organizations_pkey PRIMARY KEY (id)
);

ALTER TABLE public.users ADD CONSTRAINT users_organization_fkey
    FOREIGN KEY (organization_id) REFERENCES public.organizations (id)
    ON UPDATE NO ACTION ON DELETE NO ACTION;

ALTER TABLE public.organizations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.organizations FROM PUBLIC, anon, authenticated;
-- No browser policies. Backend JDBC grants/RLS strategy are reviewed separately, as in V001.
COMMIT;
