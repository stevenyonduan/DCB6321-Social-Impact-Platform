-- Apply manually once, after review, in Supabase SQL Editor.
-- Requires the existing Supabase-managed auth.users table. Never creates auth identities.
-- Deliberately fails if public.users already exists; inspect drift instead of masking it.
BEGIN;

CREATE TABLE public.users (
    id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    bio TEXT,
    avatar_url VARCHAR(255),
    -- Deferred FK: public.organizations does not exist yet.
    organization_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_role_check CHECK (role IN ('STANDARD', 'ADMIN', 'ORGANIZATION')),
    CONSTRAINT users_auth_user_fkey FOREIGN KEY (id)
        REFERENCES auth.users (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- No browser policies or account API are introduced in this ticket.
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.users FROM PUBLIC, anon, authenticated;

COMMIT;
