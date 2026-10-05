-- Niches, memberships, the follow graph, moderators, reserved handles, and profile counters.
--
-- Access model for every table here:
--   * RLS is enabled AND forced, with no policy for anon / authenticated, so the
--     Data API (PostgREST) can neither read nor write anything.
--   * The Spring Boot backend connects as `api_service`, which gets exactly the
--     policies and grants it needs. Nothing can DELETE except user_niches and follows.
--   * Seeds and migrations run as the owner (postgres), which bypasses RLS.

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA extensions;

-- The backend's database role. NOLOGIN here on purpose: the login and password are
-- set outside version control (ALTER ROLE api_service WITH LOGIN PASSWORD '...').
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'api_service') THEN
        CREATE ROLE api_service NOLOGIN NOINHERIT;
    END IF;
END
$$;
-- citext's comparison operators live in `extensions`. Without it on the search_path a
-- handle or slug comparison silently becomes case-sensitive, so pin it for this role.
ALTER ROLE api_service SET search_path = public, extensions;
GRANT USAGE ON SCHEMA public TO api_service;
GRANT USAGE ON SCHEMA extensions TO api_service;

-- ---------------------------------------------------------------------------
-- profiles
-- ---------------------------------------------------------------------------
-- No earlier migration creates profiles, so create it if it is missing, then add
-- the new columns either way (a no-op where they already exist).
CREATE TABLE IF NOT EXISTS public.profiles (
    id          uuid        PRIMARY KEY REFERENCES auth.users (id) ON DELETE CASCADE,
    handle      extensions.citext UNIQUE,
    name        text,
    bio         text,
    avatar_url  text,
    cover_url   text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS verified        boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS follower_count  integer NOT NULL DEFAULT 0 CHECK (follower_count >= 0),
    ADD COLUMN IF NOT EXISTS following_count integer NOT NULL DEFAULT 0 CHECK (following_count >= 0);

-- ---------------------------------------------------------------------------
-- Niche taxonomy
-- ---------------------------------------------------------------------------
CREATE TABLE public.niche_groups (
    id          uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text    NOT NULL UNIQUE,
    strict      boolean NOT NULL DEFAULT false,  -- true: a member may pick only one niche in the group
    sort_order  integer NOT NULL DEFAULT 0
);

CREATE TABLE public.niches (
    id            uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id      uuid    REFERENCES public.niche_groups (id) ON DELETE RESTRICT,  -- null for user-created niches
    label         text    NOT NULL CHECK (length(btrim(label)) > 0),
    slug          extensions.citext NOT NULL UNIQUE,
    created_by    uuid    REFERENCES auth.users (id) ON DELETE SET NULL,
    is_system     boolean NOT NULL DEFAULT false,
    member_count  integer NOT NULL DEFAULT 0 CHECK (member_count >= 0),
    sort_order    integer NOT NULL DEFAULT 0,  -- position inside its group, as the app shows it
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX niches_group_idx ON public.niches (group_id, sort_order);

CREATE TABLE public.user_niches (
    user_id    uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    niche_id   uuid        NOT NULL REFERENCES public.niches (id) ON DELETE CASCADE,
    joined_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, niche_id)
);

CREATE INDEX user_niches_niche_idx ON public.user_niches (niche_id);

CREATE TABLE public.niche_moderators (
    niche_id    uuid        NOT NULL REFERENCES public.niches (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    granted_by  uuid        REFERENCES auth.users (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (niche_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Follow graph
-- ---------------------------------------------------------------------------
CREATE TABLE public.follows (
    follower_id  uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    followee_id  uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT follows_not_self CHECK (follower_id <> followee_id)
);

CREATE INDEX follows_followee_idx ON public.follows (followee_id);

-- ---------------------------------------------------------------------------
-- Handles nobody may claim
-- ---------------------------------------------------------------------------
CREATE TABLE public.reserved_handles (
    handle  extensions.citext PRIMARY KEY CHECK (length(handle) > 0)
);

-- ---------------------------------------------------------------------------
-- Access control
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['profiles', 'niche_groups', 'niches', 'user_niches',
                             'niche_moderators', 'follows', 'reserved_handles']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, anon, authenticated', t);
    END LOOP;
END
$$;

-- What api_service may do, per table. A privilege and a matching policy for each.
-- select
GRANT SELECT ON public.profiles, public.niche_groups, public.niches, public.user_niches,
                public.niche_moderators, public.follows, public.reserved_handles TO api_service;
CREATE POLICY api_service_select ON public.profiles         FOR SELECT TO api_service USING (true);
CREATE POLICY api_service_select ON public.niche_groups     FOR SELECT TO api_service USING (true);
CREATE POLICY api_service_select ON public.niches           FOR SELECT TO api_service USING (true);
CREATE POLICY api_service_select ON public.user_niches      FOR SELECT TO api_service USING (true);
CREATE POLICY api_service_select ON public.niche_moderators FOR SELECT TO api_service USING (true);
CREATE POLICY api_service_select ON public.follows          FOR SELECT TO api_service USING (true);
CREATE POLICY api_service_select ON public.reserved_handles FOR SELECT TO api_service USING (true);

-- insert
GRANT INSERT ON public.profiles, public.niches, public.user_niches,
                public.niche_moderators, public.follows TO api_service;
CREATE POLICY api_service_insert ON public.profiles         FOR INSERT TO api_service WITH CHECK (true);
CREATE POLICY api_service_insert ON public.niches           FOR INSERT TO api_service WITH CHECK (true);
CREATE POLICY api_service_insert ON public.user_niches      FOR INSERT TO api_service WITH CHECK (true);
CREATE POLICY api_service_insert ON public.niche_moderators FOR INSERT TO api_service WITH CHECK (true);
CREATE POLICY api_service_insert ON public.follows          FOR INSERT TO api_service WITH CHECK (true);

-- update: profiles (counters, edits) and niches (member_count). Follows and memberships
-- are only ever created or deleted.
GRANT UPDATE ON public.profiles, public.niches TO api_service;
CREATE POLICY api_service_update ON public.profiles FOR UPDATE TO api_service USING (true) WITH CHECK (true);
CREATE POLICY api_service_update ON public.niches   FOR UPDATE TO api_service USING (true) WITH CHECK (true);

-- delete: only leaving a niche and unfollowing.
GRANT DELETE ON public.user_niches, public.follows TO api_service;
CREATE POLICY api_service_delete ON public.user_niches FOR DELETE TO api_service USING (true);
CREATE POLICY api_service_delete ON public.follows     FOR DELETE TO api_service USING (true);
