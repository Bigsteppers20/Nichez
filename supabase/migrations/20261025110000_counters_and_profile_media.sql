-- Shared counter triggers, follow and niche-member counters, profile images, account state.
--
--   public.bump_counter()    generic trigger: +1 on insert, -1 on delete of the row, applied to a
--                            counter column of the parent row. Arguments: parent table, counter
--                            column, the column of the child row that holds the parent's id.
--   public.reset_counter()   generic TRUNCATE trigger that zeroes a counter column on the parent.
--   follows / user_niches    keep profiles.follower_count / following_count and niches.member_count.
--   profiles                 avatar and cover media, and when the account was deactivated or asked
--                            to be deleted (the account service hides such accounts everywhere).
--   media.has_audio          drives the "tap for audio" chip; set by the processing worker.

-- ---------------------------------------------------------------------------
-- Generic counters
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.bump_counter() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
DECLARE
    parent_id uuid;
    delta     integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        delta := 1;
        parent_id := (to_jsonb(NEW) ->> TG_ARGV[2])::uuid;
    ELSE
        delta := -1;
        parent_id := (to_jsonb(OLD) ->> TG_ARGV[2])::uuid;
    END IF;
    -- GREATEST keeps a counter that is already off from failing its CHECK (>= 0) and aborting the write.
    EXECUTE format('UPDATE public.%I SET %I = GREATEST(%I + $1, 0) WHERE id = $2', TG_ARGV[0], TG_ARGV[1], TG_ARGV[1])
        USING delta, parent_id;
    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION public.reset_counter() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    EXECUTE format('UPDATE public.%I SET %I = 0', TG_ARGV[0], TG_ARGV[1]);
    RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION public.bump_counter() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.reset_counter() FROM PUBLIC, anon, authenticated;

-- ---------------------------------------------------------------------------
-- niches.member_count
-- ---------------------------------------------------------------------------
UPDATE public.niches n
SET member_count = c.n
FROM (SELECT niche_id, count(*)::integer AS n FROM public.user_niches GROUP BY niche_id) c
WHERE c.niche_id = n.id;

CREATE TRIGGER user_niches_bump_member_count
    AFTER INSERT OR DELETE ON public.user_niches
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('niches', 'member_count', 'niche_id');

CREATE TRIGGER user_niches_reset_member_count
    AFTER TRUNCATE ON public.user_niches
    FOR EACH STATEMENT EXECUTE FUNCTION public.reset_counter('niches', 'member_count');

-- The trending list counts recent joins.
CREATE INDEX user_niches_joined_at_idx ON public.user_niches (joined_at DESC);

-- ---------------------------------------------------------------------------
-- profiles.follower_count / following_count
-- ---------------------------------------------------------------------------
UPDATE public.profiles p
SET follower_count = c.n
FROM (SELECT followee_id, count(*)::integer AS n FROM public.follows GROUP BY followee_id) c
WHERE c.followee_id = p.id;

UPDATE public.profiles p
SET following_count = c.n
FROM (SELECT follower_id, count(*)::integer AS n FROM public.follows GROUP BY follower_id) c
WHERE c.follower_id = p.id;

CREATE OR REPLACE FUNCTION public.follows_track_counts() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE public.profiles SET follower_count = follower_count + 1 WHERE id = NEW.followee_id;
        UPDATE public.profiles SET following_count = following_count + 1 WHERE id = NEW.follower_id;
    ELSE
        UPDATE public.profiles SET follower_count = GREATEST(follower_count - 1, 0) WHERE id = OLD.followee_id;
        UPDATE public.profiles SET following_count = GREATEST(following_count - 1, 0) WHERE id = OLD.follower_id;
    END IF;
    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION public.follows_reset_counts() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    UPDATE public.profiles SET follower_count = 0, following_count = 0;
    RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION public.follows_track_counts() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.follows_reset_counts() FROM PUBLIC, anon, authenticated;

CREATE TRIGGER follows_track_counts
    AFTER INSERT OR DELETE ON public.follows
    FOR EACH ROW EXECUTE FUNCTION public.follows_track_counts();

CREATE TRIGGER follows_reset_counts
    AFTER TRUNCATE ON public.follows
    FOR EACH STATEMENT EXECUTE FUNCTION public.follows_reset_counts();

-- ---------------------------------------------------------------------------
-- Profile images and account state
-- ---------------------------------------------------------------------------
ALTER TABLE public.media
    ADD COLUMN has_audio boolean NOT NULL DEFAULT false;

ALTER TABLE public.profiles
    ADD COLUMN avatar_media_id       uuid REFERENCES public.media (id) ON DELETE SET NULL,
    ADD COLUMN cover_media_id        uuid REFERENCES public.media (id) ON DELETE SET NULL,
    ADD COLUMN deactivated_at        timestamptz,   -- hidden until the next sign-in
    ADD COLUMN deletion_requested_at timestamptz,   -- hidden at once; erased after the grace period
    ADD COLUMN deleted_at            timestamptz;   -- erased: personal data cleared, content taken down

CREATE INDEX profiles_deletion_due_idx ON public.profiles (deletion_requested_at)
    WHERE deletion_requested_at IS NOT NULL AND deleted_at IS NULL;
