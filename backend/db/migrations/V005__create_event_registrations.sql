-- Apply manually once after V001–V004. Never rerun or edit applied migrations.
BEGIN;

CREATE TABLE public.event_registrations (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT event_registrations_pkey PRIMARY KEY (id),
    CONSTRAINT event_registrations_event_user_key UNIQUE (event_id, user_id),
    CONSTRAINT event_registrations_event_fkey FOREIGN KEY (event_id)
        REFERENCES public.events (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT event_registrations_user_fkey FOREIGN KEY (user_id)
        REFERENCES public.users (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- Support attendee and personal registration pagination with a deterministic tie-breaker.
CREATE INDEX event_registrations_event_time_id_idx
    ON public.event_registrations (event_id, registered_at, id);
CREATE INDEX event_registrations_user_time_id_idx
    ON public.event_registrations (user_id, registered_at, id);
ALTER TABLE public.event_registrations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.event_registrations FROM PUBLIC, anon, authenticated;
-- No browser policies. Review backend JDBC grants and effective RLS access separately.
COMMIT;
