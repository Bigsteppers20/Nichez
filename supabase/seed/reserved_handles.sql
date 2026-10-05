-- Handles nobody may claim: brand, staff and role names, plus words that would
-- collide with app routes or look like official accounts. Matching is case-insensitive.
-- Safe to run more than once. To reserve more, add a row here (or insert it directly).

INSERT INTO public.reserved_handles (handle) VALUES
    ('admin'), ('administrator'), ('root'), ('system'), ('staff'), ('team'), ('official'),
    ('moderator'), ('mod'), ('mods'), ('support'), ('help'), ('safety'), ('security'),
    ('abuse'), ('report'), ('legal'), ('press'), ('contact'), ('billing'), ('wallet'),
    ('nichez'), ('nichezapp'), ('nichez_official'),
    ('api'), ('www'), ('mail'), ('app'), ('web'),
    ('about'), ('terms'), ('privacy'), ('settings'), ('explore'), ('search'), ('feed'),
    ('home'), ('me'), ('login'), ('logout'), ('signin'), ('signup'), ('verify'),
    ('null'), ('undefined'), ('anonymous')
ON CONFLICT (handle) DO NOTHING;
