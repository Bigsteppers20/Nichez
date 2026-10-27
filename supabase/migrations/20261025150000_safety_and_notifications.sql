-- Blocks (two-way), mutes (one-way), reports and moderator verdicts, content preferences,
-- and the activity feed with push devices and settings.
--
-- A block hides each person from the other everywhere; a mute only hides the muted person's
-- posts from the muter. Neither is ever revealed to the person on the other side.

CREATE TABLE public.blocks (
    blocker_id  uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    blocked_id  uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX blocks_blocked_idx ON public.blocks (blocked_id);

CREATE TABLE public.mutes (
    muter_id    uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    muted_id    uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (muter_id, muted_id),
    CONSTRAINT mutes_not_self CHECK (muter_id <> muted_id)
);

CREATE INDEX mutes_muted_idx ON public.mutes (muted_id);

-- ---------------------------------------------------------------------------
-- reports and verdicts
-- ---------------------------------------------------------------------------
-- One report per reporter per target. A moderator's verdict resolves every open report on the same target.
CREATE TABLE public.reports (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id   uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    target_type   text        NOT NULL CHECK (target_type IN ('post', 'comment', 'user', 'community')),
    target_id     uuid        NOT NULL,
    niche_id      uuid        REFERENCES public.niches (id),          -- whose moderators handle it
    community_id  uuid        REFERENCES public.communities (id),     -- or this community's
    reason_id     text        NOT NULL CHECK (reason_id IN ('hate', 'wrong-niche', 'nudity', 'spam', 'religious', 'other')),
    note          text        CHECK (char_length(note) <= 500),
    severe        boolean     NOT NULL DEFAULT false,                  -- reasons that target a person
    status        text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    verdict       text        CHECK (verdict IN ('keep', 'remove', 'ban')),
    resolved_by   uuid        REFERENCES public.profiles (id),
    resolved_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reporter_id, target_type, target_id),
    CONSTRAINT reports_resolved_has_verdict CHECK (
        status = 'open' OR (verdict IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX reports_target_idx ON public.reports (target_type, target_id);
CREATE INDEX reports_niche_open_idx ON public.reports (niche_id, created_at, id) WHERE status = 'open';
CREATE INDEX reports_community_open_idx ON public.reports (community_id, created_at, id) WHERE status = 'open';
CREATE INDEX reports_resolved_idx ON public.reports (resolved_at DESC) WHERE status = 'resolved';

-- What a moderator decided, kept as the record. Never changed or deleted.
CREATE TABLE public.moderation_actions (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id     uuid        NOT NULL REFERENCES public.reports (id),
    actor_id      uuid        NOT NULL REFERENCES public.profiles (id),
    verdict       text        NOT NULL CHECK (verdict IN ('keep', 'remove', 'ban')),
    target_type   text        NOT NULL,
    target_id     uuid        NOT NULL,
    niche_id      uuid        REFERENCES public.niches (id),
    community_id  uuid        REFERENCES public.communities (id),
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- A ban keeps a person out of one niche or community without touching their account: they cannot
-- join or post there. Written by the "ban" verdict.
CREATE TABLE public.bans (
    scope_type  text        NOT NULL CHECK (scope_type IN ('niche', 'community')),
    scope_id    uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    banned_by   uuid        NOT NULL REFERENCES public.profiles (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_type, scope_id, user_id)
);

CREATE INDEX bans_user_idx ON public.bans (user_id);

CREATE TABLE public.content_preferences (
    user_id            uuid        PRIMARY KEY REFERENCES public.profiles (id) ON DELETE CASCADE,
    sensitive_filter   text        NOT NULL DEFAULT 'veil' CHECK (sensitive_filter IN ('veil', 'hide', 'show')),
    auto_hide_reported boolean     NOT NULL DEFAULT true,
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER content_preferences_set_updated_at
    BEFORE UPDATE ON public.content_preferences
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ---------------------------------------------------------------------------
-- notifications and push
-- ---------------------------------------------------------------------------
CREATE TABLE public.notifications (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,  -- who it is for
    actor_id    uuid        REFERENCES public.profiles (id) ON DELETE CASCADE,           -- who did it
    kind        text        NOT NULL CHECK (kind IN ('reaction', 'follow', 'reshare', 'comment', 'mention')),
    post_id     uuid        REFERENCES public.posts (id) ON DELETE CASCADE,
    comment_id  uuid        REFERENCES public.comments (id) ON DELETE CASCADE,
    niche_id    uuid        REFERENCES public.niches (id),  -- the push title is the niche name, not "Nichez"
    message     text        NOT NULL CHECK (char_length(message) <= 200),  -- continues the actor's name
    read_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_not_to_self CHECK (actor_id IS DISTINCT FROM user_id)
);

CREATE INDEX notifications_user_idx ON public.notifications (user_id, created_at DESC, id DESC);
CREATE INDEX notifications_unread_idx ON public.notifications (user_id) WHERE read_at IS NULL;
-- Reacting, following and resharing notify once per person and post, however often it is toggled.
CREATE UNIQUE INDEX notifications_once_idx
    ON public.notifications (user_id, actor_id, kind, coalesce(post_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE kind IN ('reaction', 'follow', 'reshare');

CREATE TABLE public.devices (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
    token         text        NOT NULL UNIQUE CHECK (length(token) BETWEEN 8 AND 4096),
    platform      text        NOT NULL CHECK (platform IN ('android', 'ios', 'web')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX devices_user_idx ON public.devices (user_id);

CREATE TABLE public.notification_settings (
    user_id             uuid        PRIMARY KEY REFERENCES public.profiles (id) ON DELETE CASCADE,
    push_reactions      boolean     NOT NULL DEFAULT true,
    push_comments       boolean     NOT NULL DEFAULT true,
    push_follows        boolean     NOT NULL DEFAULT true,
    push_mentions       boolean     NOT NULL DEFAULT true,
    push_reshares       boolean     NOT NULL DEFAULT true,
    quiet_hours_enabled boolean     NOT NULL DEFAULT false,
    quiet_hours_start   time        NOT NULL DEFAULT '22:00',
    quiet_hours_end     time        NOT NULL DEFAULT '07:00',
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER notification_settings_set_updated_at
    BEFORE UPDATE ON public.notification_settings
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ---------------------------------------------------------------------------
-- Access
-- ---------------------------------------------------------------------------
SELECT public.grant_api_service('public.blocks',                ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.mutes',                 ARRAY['select', 'insert', 'delete']);
SELECT public.grant_api_service('public.reports',               ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.moderation_actions',    ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.bans',                  ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.content_preferences',   ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.notifications',         ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.devices',               ARRAY['select', 'insert', 'update', 'delete']);
SELECT public.grant_api_service('public.notification_settings', ARRAY['select', 'insert', 'update']);

INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('blocks',                'safety-moderation',  '20261025150000'),
    ('mutes',                 'safety-moderation',  '20261025150000'),
    ('reports',               'safety-moderation',  '20261025150000'),
    ('moderation_actions',    'safety-moderation',  '20261025150000'),
    ('bans',                  'safety-moderation',  '20261025150000'),
    ('content_preferences',   'safety-moderation',  '20261025150000'),
    ('notifications',         'notifications-push', '20261025150000'),
    ('devices',               'notifications-push', '20261025150000'),
    ('notification_settings', 'notifications-push', '20261025150000');
