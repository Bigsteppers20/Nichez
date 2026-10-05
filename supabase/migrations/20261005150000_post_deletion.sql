-- Post deletion: the author's post counter, and what the media purge job needs.
--
--   profiles.post_count  published posts by this user (UserProfile.postCount). Kept by a trigger
--                        on posts, so every path that publishes, deletes or removes a post moves it
--                        in the same transaction, and two writers cannot overwrite each other.
--   media.purged_at      when the purge job deleted this media's stored objects. Null until then.

-- ---------------------------------------------------------------------------
-- profiles.post_count
-- ---------------------------------------------------------------------------
ALTER TABLE public.profiles
    ADD COLUMN post_count integer NOT NULL DEFAULT 0 CHECK (post_count >= 0);

UPDATE public.profiles p
SET post_count = c.n
FROM (SELECT author_id, count(*)::integer AS n
      FROM public.posts
      WHERE status = 'published'
      GROUP BY author_id) c
WHERE c.author_id = p.id;

-- Counts a post while its status is 'published': +1 on entering it, -1 on leaving it.
CREATE OR REPLACE FUNCTION public.posts_track_post_count() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status = 'published' THEN
            UPDATE public.profiles SET post_count = post_count + 1 WHERE id = NEW.author_id;
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NEW.status = 'published' THEN
            UPDATE public.profiles SET post_count = post_count + 1 WHERE id = NEW.author_id;
        ELSIF OLD.status = 'published' THEN
            UPDATE public.profiles SET post_count = post_count - 1 WHERE id = NEW.author_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION public.posts_track_post_count() FROM PUBLIC, anon, authenticated;

CREATE TRIGGER posts_track_post_count
    AFTER INSERT OR UPDATE OF status ON public.posts
    FOR EACH ROW EXECUTE FUNCTION public.posts_track_post_count();

-- ---------------------------------------------------------------------------
-- Purge of deleted and removed posts' media
-- ---------------------------------------------------------------------------
ALTER TABLE public.media
    ADD COLUMN purged_at timestamptz;

-- The purge job's scan: taken-down posts, oldest first.
CREATE INDEX posts_taken_down_idx ON public.posts (deleted_at)
    WHERE status IN ('deleted', 'removed');
