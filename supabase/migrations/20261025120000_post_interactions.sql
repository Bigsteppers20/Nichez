-- What people do with a post: react, save (into collections), reshare, share out, cross-post into
-- another niche, mark "not interested", and watch. Counters on posts are kept by triggers, so
-- two simultaneous writers cannot overwrite each other.
--
-- Access: every table is locked down with grant_api_service; the backend gets only the commands
-- listed at the end of this file.

ALTER TABLE public.posts
    ADD COLUMN reshare_count integer NOT NULL DEFAULT 0 CHECK (reshare_count >= 0),
    ADD COLUMN share_count   integer NOT NULL DEFAULT 0 CHECK (share_count >= 0),
    ADD COLUMN save_count    integer NOT NULL DEFAULT 0 CHECK (save_count >= 0),
    ADD COLUMN view_count    bigint  NOT NULL DEFAULT 0 CHECK (view_count >= 0);

-- ---------------------------------------------------------------------------
-- reactions: one per user per post, one of six kinds; changing the kind keeps the row
-- ---------------------------------------------------------------------------
CREATE TABLE public.reactions (
    post_id     uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    kind        text        NOT NULL CHECK (kind IN ('love', 'laugh', 'fire', 'cry', 'clap', 'dead')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX reactions_user_idx ON public.reactions (user_id, created_at DESC);

CREATE TRIGGER reactions_set_updated_at
    BEFORE UPDATE ON public.reactions
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER reactions_bump_reaction_count
    AFTER INSERT OR DELETE ON public.reactions
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('posts', 'reaction_count', 'post_id');

-- ---------------------------------------------------------------------------
-- saves and collections
-- ---------------------------------------------------------------------------
CREATE TABLE public.saves (
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    post_id     uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

CREATE INDEX saves_user_created_idx ON public.saves (user_id, created_at DESC, post_id DESC);
CREATE INDEX saves_post_idx ON public.saves (post_id);

CREATE TRIGGER saves_bump_save_count
    AFTER INSERT OR DELETE ON public.saves
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('posts', 'save_count', 'post_id');

CREATE TABLE public.collections (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    name        text        NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 40),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- A person's collection names are unique ignoring case.
CREATE UNIQUE INDEX collections_owner_name_idx ON public.collections (owner_id, lower(name));

CREATE TRIGGER collections_set_updated_at
    BEFORE UPDATE ON public.collections
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE public.collection_posts (
    collection_id  uuid        NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    post_id        uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    added_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, post_id)
);

CREATE INDEX collection_posts_collection_idx ON public.collection_posts (collection_id, added_at DESC, post_id DESC);
CREATE INDEX collection_posts_post_idx ON public.collection_posts (post_id);

-- ---------------------------------------------------------------------------
-- reshares (to the resharer's followers) and cross-posts (into another niche)
-- ---------------------------------------------------------------------------
CREATE TABLE public.reshares (
    post_id     uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX reshares_user_idx ON public.reshares (user_id, created_at DESC);

CREATE TRIGGER reshares_bump_reshare_count
    AFTER INSERT OR DELETE ON public.reshares
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('posts', 'reshare_count', 'post_id');

-- A post's own niche stays in posts.niche_id; each extra niche it was cross-posted into is a row here.
CREATE TABLE public.post_niches (
    post_id     uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    niche_id    uuid        NOT NULL REFERENCES public.niches (id) ON DELETE CASCADE,
    added_by    uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, niche_id)
);

CREATE INDEX post_niches_niche_idx ON public.post_niches (niche_id, created_at DESC, post_id DESC);

-- ---------------------------------------------------------------------------
-- shares (counted when someone shares out), not-interested, views
-- ---------------------------------------------------------------------------
CREATE TABLE public.shares (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id     uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    channel     text        NOT NULL CHECK (channel IN ('link', 'send', 'cross_post', 'save_image')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX shares_post_idx ON public.shares (post_id);

CREATE TRIGGER shares_bump_share_count
    AFTER INSERT ON public.shares
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('posts', 'share_count', 'post_id');

CREATE TABLE public.post_not_interested (
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    post_id     uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

-- One row per playback the app reports. The server decides what qualifies; the app is never
-- trusted. Raw and append-only: counters are derived by the trigger below.
CREATE TABLE public.view_events (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id        uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    viewer_id      uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    watched_ms     integer     NOT NULL CHECK (watched_ms >= 0),
    qualifies      boolean     NOT NULL,
    reject_reason  text        CHECK (reject_reason IN ('too_short', 'self_view', 'duplicate_today')),
    day            date        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT view_events_reason_matches CHECK (qualifies = (reject_reason IS NULL))
);

-- At most one qualifying view per viewer per post per day.
CREATE UNIQUE INDEX view_events_one_per_day_idx ON public.view_events (post_id, viewer_id, day) WHERE qualifies;
CREATE INDEX view_events_viewer_idx ON public.view_events (viewer_id, created_at DESC);

-- Rollup, one row per post per day: what the analytics sheet reads.
CREATE TABLE public.post_daily_views (
    post_id           uuid    NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    day               date    NOT NULL,
    qualifying_views  integer NOT NULL DEFAULT 0 CHECK (qualifying_views >= 0),
    PRIMARY KEY (post_id, day)
);

CREATE OR REPLACE FUNCTION public.view_events_roll_up() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    IF NEW.qualifies THEN
        UPDATE public.posts SET view_count = view_count + 1 WHERE id = NEW.post_id;
        INSERT INTO public.post_daily_views (post_id, day, qualifying_views)
        VALUES (NEW.post_id, NEW.day, 1)
        ON CONFLICT (post_id, day) DO UPDATE
            SET qualifying_views = public.post_daily_views.qualifying_views + 1;
    END IF;
    RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION public.view_events_roll_up() FROM PUBLIC, anon, authenticated;

CREATE TRIGGER view_events_roll_up
    AFTER INSERT ON public.view_events
    FOR EACH ROW EXECUTE FUNCTION public.view_events_roll_up();

-- ---------------------------------------------------------------------------
-- Access
-- ---------------------------------------------------------------------------
SELECT public.grant_api_service('public.reactions',           ARRAY['select', 'insert', 'update', 'delete']);
SELECT public.grant_api_service('public.saves',               ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.collections',         ARRAY['select', 'insert', 'update', 'delete']);
SELECT public.grant_api_service('public.collection_posts',    ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.reshares',            ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.post_niches',         ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.shares',              ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.post_not_interested', ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.view_events',         ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.post_daily_views',    ARRAY['select', 'insert', 'update']);

INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('reactions',           'home-feed',         '20261025120000'),
    ('reshares',            'home-feed',         '20261025120000'),
    ('post_niches',         'home-feed',         '20261025120000'),
    ('shares',              'home-feed',         '20261025120000'),
    ('post_not_interested', 'home-feed',         '20261025120000'),
    ('view_events',         'home-feed',         '20261025120000'),
    ('post_daily_views',    'post-detail',       '20261025120000'),
    ('saves',               'saved-collections', '20261025120000'),
    ('collections',         'saved-collections', '20261025120000'),
    ('collection_posts',    'saved-collections', '20261025120000');
