-- Updated in unmerged PR #19: V003 has not been applied to Supabase.
-- Apply manually once after V001/V002. Never rerun or edit applied migrations.
-- NO ACTION preserves related records until deletion/update workflows are approved.
BEGIN;

CREATE TABLE public.events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    creator_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    image_url VARCHAR(255),
    event_start TIMESTAMPTZ NOT NULL,
    event_end TIMESTAMPTZ NOT NULL,
    location VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Newly approved PR #19 extensions, not original DDD columns.
    registration_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    registration_url TEXT,
    CONSTRAINT events_registration_type_check CHECK (registration_type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT events_registration_url_check CHECK (
        (registration_type = 'INTERNAL' AND registration_url IS NULL)
        OR (registration_type = 'EXTERNAL' AND registration_url IS NOT NULL
            AND registration_url ~ '[^[:space:]]')
    ),
    CONSTRAINT events_pkey PRIMARY KEY (id),
    CONSTRAINT events_organization_fkey FOREIGN KEY (organization_id)
        REFERENCES public.organizations (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT events_creator_fkey FOREIGN KEY (creator_id)
        REFERENCES public.users (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- Supports organization-scoped, stable chronological pagination without a full-table sort.
CREATE INDEX events_organization_start_id_idx ON public.events (organization_id, event_start, id);
ALTER TABLE public.events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.events FROM PUBLIC, anon, authenticated;
-- No browser policies. Review backend JDBC grants and RLS access separately.
COMMIT;
