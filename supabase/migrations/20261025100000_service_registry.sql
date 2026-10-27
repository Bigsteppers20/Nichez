-- Service registry: which API area ("service") owns which table.
--
-- A service is an API area: a group of endpoints under /api/v1 that receive requests and send
-- payloads, as grouped in docs/FEATURE_MANIFEST.md section 3. The Postgres catalog already holds
-- the live columns and the migration files already hold the history, so this records the one
-- thing neither can: ownership. A schema test (ServiceRegistrySchemaTests) fails when a table in
-- public has no owner, so every migration that creates a table must also add its service_tables row.

CREATE TABLE public.api_services (
    slug             text        PRIMARY KEY CHECK (slug ~ '^[a-z][a-z0-9-]*$'),
    name             text        NOT NULL CHECK (length(btrim(name)) > 0),
    manifest_section text        NOT NULL UNIQUE,  -- the heading number in FEATURE_MANIFEST.md, e.g. '3.3'
    priority         text        NOT NULL CHECK (priority IN ('P0', 'P1', 'P2')),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.service_tables (
    table_name    text        PRIMARY KEY CHECK (table_name ~ '^[a-z][a-z0-9_]*$'),
    service_slug  text        NOT NULL REFERENCES public.api_services (slug),
    introduced_in text        NOT NULL,  -- version of the migration that created the table
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX service_tables_service_idx ON public.service_tables (service_slug);

INSERT INTO public.api_services (slug, name, manifest_section, priority) VALUES
    ('auth-session',        'Auth & session',                    '3.1',  'P0'),
    ('onboarding',          'Onboarding',                        '3.2',  'P0'),
    ('home-feed',           'Home feed',                         '3.3',  'P0'),
    ('post-detail',         'Post detail & analytics',           '3.4',  'P0'),
    ('comments',            'Comments',                          '3.5',  'P0'),
    ('creating-posts',      'Creating posts',                    '3.6',  'P0'),
    ('live',                'Live',                              '3.7',  'P1'),
    ('explore-search',      'Explore & search',                  '3.8',  'P0'),
    ('communities',         'Communities (niches)',              '3.9',  'P0'),
    ('profiles-follows',    'Profiles & follows',                '3.10', 'P0'),
    ('saved-collections',   'Saved & collections',               '3.11', 'P1'),
    ('notifications-push',  'Notifications & push',              '3.12', 'P0'),
    ('safety-moderation',   'Safety & moderation',               '3.13', 'P0'),
    ('settings-account',    'Settings & account',                '3.14', 'P0'),
    ('kyc',                 'KYC',                               '3.15', 'P1'),
    ('wallet-payouts',      'Earnings, wallet & payouts',        '3.16', 'P1'),
    ('direct-messages',     'Direct messages',                   '3.17', 'P2'),
    ('app-config',          'App config',                        '3.18', 'P0');

-- The tables that existed before the registry.
INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('api_services',           'app-config',        '20261025100000'),
    ('service_tables',         'app-config',        '20261025100000'),
    ('phone_challenges',       'auth-session',      '20261004120000'),
    ('phone_otp_sends',        'auth-session',      '20261004120000'),
    ('audit_events',           'safety-moderation', '20261004120000'),
    ('profiles',               'profiles-follows',  '20261005120000'),
    ('follows',                'profiles-follows',  '20261005120000'),
    ('reserved_handles',       'profiles-follows',  '20261005120000'),
    ('niche_groups',           'communities',       '20261005120000'),
    ('niches',                 'communities',       '20261005120000'),
    ('user_niches',            'communities',       '20261005120000'),
    ('niche_moderators',       'communities',       '20261005120000'),
    ('media',                  'creating-posts',    '20261005140000'),
    ('posts',                  'creating-posts',    '20261005140000'),
    ('post_tags',              'creating-posts',    '20261005140000'),
    ('media_processing_jobs',  'creating-posts',    '20261005160000');

-- Read-only for the backend: the registry is changed by migrations, never at runtime.
SELECT public.grant_api_service('public.api_services',  ARRAY['select']);
SELECT public.grant_api_service('public.service_tables', ARRAY['select']);
