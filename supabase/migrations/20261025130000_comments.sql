-- Comments (threaded one level deep) and reactions to comments.
--
-- posts.comment_count counts live comments, replies included; a trigger keeps it. A comment is
-- never hard-deleted: deleting sets deleted_at, which also takes the count down.
-- comments.reaction_count is the comment's score.

CREATE TABLE public.comments (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id         uuid        NOT NULL REFERENCES public.posts (id) ON DELETE CASCADE,
    author_id       uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    parent_id       uuid        REFERENCES public.comments (id) ON DELETE CASCADE,  -- null for a top-level comment
    body            text        NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 1000),
    reaction_count  integer     NOT NULL DEFAULT 0 CHECK (reaction_count >= 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    CONSTRAINT comments_not_own_parent CHECK (parent_id IS DISTINCT FROM id)
);

-- Top-level comments of a post, newest first and best first; replies of a comment in order.
CREATE INDEX comments_post_top_idx    ON public.comments (post_id, created_at DESC, id DESC) WHERE parent_id IS NULL AND deleted_at IS NULL;
CREATE INDEX comments_post_score_idx  ON public.comments (post_id, reaction_count DESC, created_at DESC, id DESC) WHERE parent_id IS NULL AND deleted_at IS NULL;
CREATE INDEX comments_parent_idx      ON public.comments (parent_id, created_at, id) WHERE parent_id IS NOT NULL;
CREATE INDEX comments_author_idx      ON public.comments (author_id, created_at DESC);

CREATE OR REPLACE FUNCTION public.comments_track_count() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.deleted_at IS NULL THEN
            UPDATE public.posts SET comment_count = comment_count + 1 WHERE id = NEW.post_id;
        END IF;
    ELSIF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
        UPDATE public.posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = NEW.post_id;
    END IF;
    RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION public.comments_track_count() FROM PUBLIC, anon, authenticated;

CREATE TRIGGER comments_track_count
    AFTER INSERT OR UPDATE OF deleted_at ON public.comments
    FOR EACH ROW EXECUTE FUNCTION public.comments_track_count();

CREATE TABLE public.comment_reactions (
    comment_id  uuid        NOT NULL REFERENCES public.comments (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    kind        text        NOT NULL DEFAULT 'love' CHECK (kind IN ('love', 'laugh', 'fire', 'cry', 'clap', 'dead')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id)
);

CREATE INDEX comment_reactions_user_idx ON public.comment_reactions (user_id);

CREATE TRIGGER comment_reactions_bump_reaction_count
    AFTER INSERT OR DELETE ON public.comment_reactions
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('comments', 'reaction_count', 'comment_id');

SELECT public.grant_api_service('public.comments',          ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.comment_reactions', ARRAY['select', 'insert', 'update', 'delete']);

INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('comments',          'comments', '20261025130000'),
    ('comment_reactions', 'comments', '20261025130000');
