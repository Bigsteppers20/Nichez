-- Communities: groups people make inside a home niche (public or private), with members and roles,
-- join requests for private ones, invitations and an ordered rulebook. Also posts that belong to a
-- community, and the search history behind type-ahead.
--
-- A community is not a niche. A niche is a topic anyone can join and post in; a community lives
-- inside one niche, and its posts carry both posts.niche_id (the home niche) and posts.community_id.

CREATE TABLE public.communities (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           text        NOT NULL CHECK (length(btrim(name)) BETWEEN 2 AND 40),
    description    text        CHECK (char_length(description) <= 300),
    emoji          text        NOT NULL DEFAULT '💬' CHECK (char_length(emoji) BETWEEN 1 AND 16),
    cover_color    text        NOT NULL DEFAULT '#E8EEF4' CHECK (cover_color ~ '^#[0-9A-Fa-f]{6}$'),
    icon_media_id  uuid        REFERENCES public.media (id) ON DELETE SET NULL,
    home_niche_id  uuid        NOT NULL REFERENCES public.niches (id),
    is_private     boolean     NOT NULL DEFAULT false,
    created_by     uuid        NOT NULL REFERENCES public.profiles (id),
    member_count   integer     NOT NULL DEFAULT 0 CHECK (member_count >= 0),
    created_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);

-- Names are unique ignoring case among communities that still exist.
CREATE UNIQUE INDEX communities_name_idx ON public.communities (lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX communities_home_niche_idx ON public.communities (home_niche_id, member_count DESC);

CREATE TABLE public.community_members (
    community_id  uuid        NOT NULL REFERENCES public.communities (id) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    role          text        NOT NULL DEFAULT 'member' CHECK (role IN ('founder', 'moderator', 'member')),
    joined_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, user_id)
);

CREATE INDEX community_members_user_idx ON public.community_members (user_id, joined_at DESC);
CREATE INDEX community_members_list_idx ON public.community_members (community_id, joined_at, user_id);
-- A community has exactly one founder.
CREATE UNIQUE INDEX community_members_one_founder_idx ON public.community_members (community_id) WHERE role = 'founder';

CREATE TRIGGER community_members_bump_member_count
    AFTER INSERT OR DELETE ON public.community_members
    FOR EACH ROW EXECUTE FUNCTION public.bump_counter('communities', 'member_count', 'community_id');

-- Someone who asked to join a private community and has not been answered yet.
CREATE TABLE public.community_requests (
    community_id  uuid        NOT NULL REFERENCES public.communities (id) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, user_id)
);

CREATE INDEX community_requests_list_idx ON public.community_requests (community_id, created_at, user_id);

CREATE TABLE public.community_invites (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    community_id  uuid        NOT NULL REFERENCES public.communities (id) ON DELETE CASCADE,
    inviter_id    uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    invitee_id    uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (community_id, invitee_id)
);

CREATE TABLE public.community_rules (
    community_id  uuid     NOT NULL REFERENCES public.communities (id) ON DELETE CASCADE,
    position      smallint NOT NULL CHECK (position >= 1),
    body          text     NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 200),
    PRIMARY KEY (community_id, position)
);

ALTER TABLE public.posts
    ADD COLUMN community_id uuid REFERENCES public.communities (id);

CREATE INDEX posts_community_published_idx ON public.posts (community_id, published_at DESC, id DESC)
    WHERE community_id IS NOT NULL;

-- What a person searched for, newest first; type-ahead offers their own past queries. Stored lower-cased.
CREATE TABLE public.search_history (
    user_id      uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    query        text        NOT NULL CHECK (length(query) BETWEEN 1 AND 80 AND query = lower(btrim(query))),
    searched_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, query)
);

CREATE INDEX search_history_recent_idx ON public.search_history (user_id, searched_at DESC);

SELECT public.grant_api_service('public.communities',        ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.community_members',  ARRAY['select', 'insert', 'update', 'delete']);
SELECT public.grant_api_service('public.community_requests', ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.community_invites',  ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.community_rules',    ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.search_history',     ARRAY['select', 'insert', 'update', 'delete']);

INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('communities',        'communities',    '20261025140000'),
    ('community_members',  'communities',    '20261025140000'),
    ('community_requests', 'communities',    '20261025140000'),
    ('community_invites',  'communities',    '20261025140000'),
    ('community_rules',    'communities',    '20261025140000'),
    ('search_history',     'explore-search', '20261025140000');
