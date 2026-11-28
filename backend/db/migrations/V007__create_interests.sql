-- Apply manually once after V001–V006. Review the catalog before applying.
BEGIN;

CREATE TABLE public.interests (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

INSERT INTO public.interests (id, name) VALUES
    ('28c47059-08bf-421b-843e-4ce33c79b665', 'Animal Welfare'),
    ('96c3fda8-27c1-4da5-8a8b-c82e5e42b123', 'Arts & Culture'),
    ('54c5c26e-9fab-4e7a-bcbd-40fb5c2a522c', 'Civic Engagement'),
    ('58bfbaec-2d1c-4df3-a5f5-43b2e6dff7e4', 'Community Support'),
    ('d47a1d26-63b2-4a6e-847e-95ecac0f940a', 'Disability & Accessibility'),
    ('1e5f700b-f92f-408a-83f5-c32ce8034e60', 'Disaster Relief & Preparedness'),
    ('f2d1fbc8-67e7-4c86-8e73-5ef1f1d22856', 'Education & Youth'),
    ('c7938959-056d-4b75-86ca-f16e66cd7e9d', 'Employment & Career Readiness'),
    ('75ae3610-ac80-4a9b-b393-6f57217f0737', 'Environment & Outdoors'),
    ('a739afdb-8ed8-4814-a925-278e88c90855', 'Food Access'),
    ('19019f69-7914-4ef9-a138-1e82bf62c191', 'Health & Wellness'),
    ('c4c7997f-e036-4709-b167-3da5c68e2fb0', 'Housing & Homelessness'),
    ('a4dc6e33-2bdc-4757-88d5-392d66606ddb', 'Immigrant & Refugee Support'),
    ('b79688a1-2055-4f9c-9714-7fc61f270dd3', 'Older Adults'),
    ('e2ae572d-69c9-4685-83f1-0f54c961d57a', 'Social Justice & Advocacy'),
    ('72c342d8-3258-4ce5-97f1-69d8a17dc316', 'Sports & Recreation'),
    ('4a61d512-7bda-4049-9112-147123a1c558', 'Technology & Digital Literacy'),
    ('29bfccbf-b16d-4637-b21c-a36e2510ee9f', 'Veterans & Military Families');

CREATE TABLE public.user_interests (
    user_id UUID NOT NULL REFERENCES public.users(id),
    interest_id UUID NOT NULL REFERENCES public.interests(id),
    PRIMARY KEY (user_id, interest_id)
);

CREATE TABLE public.organization_interests (
    organization_id UUID NOT NULL REFERENCES public.organizations(id),
    interest_id UUID NOT NULL REFERENCES public.interests(id),
    PRIMARY KEY (organization_id, interest_id)
);

CREATE INDEX user_interests_interest_idx ON public.user_interests (interest_id, user_id);
CREATE INDEX organization_interests_interest_idx
    ON public.organization_interests (interest_id, organization_id);

ALTER TABLE public.interests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_interests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.organization_interests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.interests, public.user_interests, public.organization_interests
    FROM PUBLIC, anon, authenticated;

COMMIT;
