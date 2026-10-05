-- Uploaded media, posts, and tagged people.
--
-- Two helpers are created here for every later migration to reuse:
--   public.set_updated_at()                    trigger function that stamps updated_at
--   public.grant_api_service(table, commands)  locks a table down and grants the backend role
--                                              exactly the commands listed, each with a policy

-- ---------------------------------------------------------------------------
-- Reusable helpers
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    -- clock_timestamp(), not now(): now() is frozen at the start of the transaction, so two
    -- updates in one transaction would get the same stamp.
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$function$;

-- RLS enabled and forced, nothing for anon / authenticated, and for api_service only the
-- listed commands (select, insert, update, delete): a table privilege plus a matching policy
-- each. Re-running it for a table replaces its api_service policies, so it is idempotent.
CREATE OR REPLACE FUNCTION public.grant_api_service(p_table regclass, p_commands text[]) RETURNS void
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
DECLARE
    cmd text;
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format('REVOKE ALL ON %s FROM PUBLIC, anon, authenticated, api_service', p_table);

    FOREACH cmd IN ARRAY p_commands LOOP
        IF cmd NOT IN ('select', 'insert', 'update', 'delete') THEN
            RAISE EXCEPTION 'grant_api_service: unknown command %', cmd;
        END IF;
        EXECUTE format('GRANT %s ON %s TO api_service', upper(cmd), p_table);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', 'api_service_' || cmd, p_table);
        EXECUTE format('CREATE POLICY %I ON %s FOR %s TO api_service %s',
                       'api_service_' || cmd, p_table, upper(cmd),
                       CASE cmd
                           WHEN 'insert' THEN 'WITH CHECK (true)'
                           WHEN 'update' THEN 'USING (true) WITH CHECK (true)'
                           ELSE 'USING (true)'
                       END);
    END LOOP;
END;
$function$;

REVOKE ALL ON FUNCTION public.set_updated_at() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.grant_api_service(regclass, text[]) FROM PUBLIC, anon, authenticated;

-- ---------------------------------------------------------------------------
-- media: one row per uploaded file, from "pending" until the worker has processed it
-- ---------------------------------------------------------------------------
CREATE TABLE public.media (
    id                uuid        PRIMARY KEY,
    owner_id          uuid        NOT NULL REFERENCES public.profiles (id),
    kind              text        NOT NULL CHECK (kind IN ('image', 'video')),
    declared_type     text,                    -- content type the client claimed, never trusted
    original_key      text,                    -- object key of the raw upload
    final_key         text,                    -- object key of the processed file
    poster_key        text,                    -- object key of the video poster frame
    bytes             bigint      CHECK (bytes >= 0),
    width             integer     CHECK (width > 0),
    height            integer     CHECK (height > 0),
    duration_seconds  integer     CHECK (duration_seconds >= 0),
    status            text        NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending', 'uploaded', 'processing', 'ready', 'failed')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX media_owner_idx ON public.media (owner_id, created_at DESC);
-- Two rows must never claim the same stored object.
CREATE UNIQUE INDEX media_original_key_idx ON public.media (original_key) WHERE original_key IS NOT NULL;

CREATE TRIGGER media_set_updated_at
    BEFORE UPDATE ON public.media
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ---------------------------------------------------------------------------
-- posts
-- ---------------------------------------------------------------------------
CREATE TABLE public.posts (
    id               uuid        PRIMARY KEY,
    author_id        uuid        NOT NULL REFERENCES public.profiles (id),
    niche_id         uuid        NOT NULL REFERENCES public.niches (id),
    media_id         uuid        NOT NULL UNIQUE REFERENCES public.media (id),
    caption          text        CHECK (char_length(caption) <= 2200),
    -- Who can see it: everyone, or only members of the niche it was posted to.
    audience         text        NOT NULL DEFAULT 'everyone' CHECK (audience IN ('everyone', 'niche_members')),
    allow_comments   boolean     NOT NULL DEFAULT true,
    sensitive        boolean     NOT NULL DEFAULT false,
    sensitive_reason text,
    status           text        NOT NULL DEFAULT 'processing'
                         CHECK (status IN ('processing', 'published', 'failed', 'removed', 'deleted')),
    reaction_count   integer     NOT NULL DEFAULT 0 CHECK (reaction_count >= 0),
    comment_count    integer     NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
    reshare_of       uuid        REFERENCES public.posts (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    published_at     timestamptz,
    deleted_at       timestamptz,
    CONSTRAINT posts_published_has_time CHECK (status <> 'published' OR published_at IS NOT NULL),
    CONSTRAINT posts_deleted_has_time   CHECK (status <> 'deleted'   OR deleted_at   IS NOT NULL),
    CONSTRAINT posts_not_own_reshare    CHECK (reshare_of IS DISTINCT FROM id)
);

CREATE INDEX posts_niche_status_published_idx  ON public.posts (niche_id,  status, published_at DESC);
CREATE INDEX posts_author_published_idx        ON public.posts (author_id, published_at DESC);
CREATE INDEX posts_status_published_idx        ON public.posts (status, published_at DESC);

-- ---------------------------------------------------------------------------
-- post_tags: people tagged in a post
-- ---------------------------------------------------------------------------
CREATE TABLE public.post_tags (
    post_id  uuid NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    user_id  uuid NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX post_tags_user_idx ON public.post_tags (user_id);

-- ---------------------------------------------------------------------------
-- Access: what the backend role may do. Nothing can be deleted: posts are soft-deleted.
-- ---------------------------------------------------------------------------
SELECT public.grant_api_service('public.media',     ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.posts',     ARRAY['select', 'insert', 'update']);
-- Tags have no column to update. Removing a tag would need a delete grant, added when editing exists.
SELECT public.grant_api_service('public.post_tags', ARRAY['select', 'insert']);
