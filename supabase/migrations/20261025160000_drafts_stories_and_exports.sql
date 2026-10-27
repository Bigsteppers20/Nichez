-- Drafts, 24-hour stories, and data-export requests.
--
-- A story is either a media story (media_id) or a text story (body and color). It expires 24 hours
-- after it is posted; readers filter on expires_at, nothing has to run for it to disappear.

CREATE TABLE public.drafts (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    media_id        uuid        REFERENCES public.media (id) ON DELETE SET NULL,
    niche_id        uuid        REFERENCES public.niches (id) ON DELETE SET NULL,
    caption         text        CHECK (char_length(caption) <= 2200),
    audience        text        NOT NULL DEFAULT 'everyone' CHECK (audience IN ('everyone', 'niche_members')),
    allow_comments  boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX drafts_owner_idx ON public.drafts (owner_id, updated_at DESC, id DESC);

CREATE TRIGGER drafts_set_updated_at
    BEFORE UPDATE ON public.drafts
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE public.stories (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id       uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    media_id        uuid        UNIQUE REFERENCES public.media (id),
    body            text        CHECK (char_length(body) BETWEEN 1 AND 280),
    color           text        CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    allow_replies   boolean     NOT NULL DEFAULT true,
    allow_sharing   boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    CONSTRAINT stories_has_content CHECK (media_id IS NOT NULL OR (body IS NOT NULL AND color IS NOT NULL)),
    CONSTRAINT stories_expires_after_created CHECK (expires_at > created_at)
);

CREATE INDEX stories_author_idx ON public.stories (author_id, created_at DESC);
CREATE INDEX stories_expires_idx ON public.stories (expires_at);

-- People the author hid this story from.
CREATE TABLE public.story_hidden_from (
    story_id  uuid NOT NULL REFERENCES public.stories (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    PRIMARY KEY (story_id, user_id)
);

CREATE TABLE public.story_views (
    story_id   uuid        NOT NULL REFERENCES public.stories (id) ON DELETE CASCADE,
    viewer_id  uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    viewed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, viewer_id)
);

CREATE INDEX story_views_viewer_idx ON public.story_views (viewer_id, viewed_at DESC);

-- "Download my data": the request is recorded here; a job fulfils it and emails the download.
CREATE TABLE public.data_export_requests (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    status        text        NOT NULL DEFAULT 'requested' CHECK (status IN ('requested', 'ready', 'failed')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz
);

CREATE INDEX data_export_requests_user_idx ON public.data_export_requests (user_id, created_at DESC);
-- One open request per person at a time.
CREATE UNIQUE INDEX data_export_requests_one_open_idx ON public.data_export_requests (user_id) WHERE status = 'requested';

SELECT public.grant_api_service('public.drafts',               ARRAY['select', 'insert', 'update', 'delete']);
SELECT public.grant_api_service('public.stories',              ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.story_hidden_from',    ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.story_views',          ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.data_export_requests', ARRAY['select', 'insert', 'update']);

INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('drafts',               'creating-posts',   '20261025160000'),
    ('stories',              'creating-posts',   '20261025160000'),
    ('story_hidden_from',    'creating-posts',   '20261025160000'),
    ('story_views',          'creating-posts',   '20261025160000'),
    ('data_export_requests', 'settings-account', '20261025160000');
