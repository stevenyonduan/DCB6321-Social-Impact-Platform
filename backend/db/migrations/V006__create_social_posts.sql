-- Apply manually once after V001–V005. No startup migration or browser access.
BEGIN;
CREATE TABLE public.posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    organization_id UUID REFERENCES public.organizations(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    text_content TEXT,
    image_url VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE public.post_interactions (
    user_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    post_id UUID NOT NULL REFERENCES public.posts(id) ON UPDATE NO ACTION ON DELETE CASCADE,
    interaction_type VARCHAR(20) NOT NULL CHECK (interaction_type IN ('LIKE', 'SAVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, post_id, interaction_type)
);
CREATE TABLE public.post_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON UPDATE NO ACTION ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES public.users(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX posts_feed_idx ON public.posts(created_at DESC, id DESC) WHERE organization_id IS NULL;
CREATE INDEX post_comments_page_idx ON public.post_comments(post_id, created_at, id);
CREATE INDEX post_interactions_counts_idx ON public.post_interactions(post_id, interaction_type);
CREATE INDEX post_interactions_personal_idx ON public.post_interactions(user_id, interaction_type, created_at DESC, post_id DESC);
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_interactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_comments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.posts, public.post_interactions, public.post_comments FROM PUBLIC, anon, authenticated;
COMMIT;
