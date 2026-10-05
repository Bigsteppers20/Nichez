-- System niche taxonomy. Mirrors the onboarding taxonomy in the Flutter app
-- (C:\Nichez\lib\dev\sample_data.dart: NicheGroup / NicheOption): same groups,
-- same strict flags, same options in the same order. The option id in the app
-- becomes the slug here.
--
-- Safe to run more than once: existing groups and niches are left as they are.

INSERT INTO public.niche_groups (name, strict, sort_order) VALUES
    ('Religion',  true,  1),
    ('Sport',     false, 2),
    ('Work-life', false, 3),
    ('Technology', false, 4)
ON CONFLICT (name) DO NOTHING;

INSERT INTO public.niches (group_id, label, slug, is_system, sort_order)
SELECT g.id, n.label, n.slug, true, n.sort_order
FROM (VALUES
    ('Religion',   'Christianity',    'christianity', 1),
    ('Religion',   'Islam',           'islam',        2),
    ('Religion',   'Faith (general)', 'faith',        3),

    ('Sport',      'Football',        'football',     1),
    ('Sport',      'Basketball',      'basketball',   2),
    ('Sport',      'Table Tennis',    'table-tennis', 3),
    ('Sport',      'Athletics',       'athletics',    4),
    ('Sport',      'Combat Sports',   'combat',       5),

    ('Work-life',  'Banking',         'banking',      1),
    ('Work-life',  'Corporate',       'corporate',    2),
    ('Work-life',  'Management',      'management',   3),
    ('Work-life',  'Remote Work',     'remote',       4),
    ('Work-life',  'Job Hunting',     'job-hunting',  5),

    ('Technology', 'Frontend',        'frontend',     1),
    ('Technology', 'Backend',         'backend',      2),
    ('Technology', 'DevOps',          'devops',       3),
    ('Technology', 'QA',              'qa',           4),
    ('Technology', 'Product',         'product',      5),
    ('Technology', 'Data & ML',       'data-ml',      6),
    ('Technology', 'Design',          'design',       7)
) AS n (group_name, label, slug, sort_order)
JOIN public.niche_groups g ON g.name = n.group_name
ON CONFLICT (slug) DO NOTHING;
