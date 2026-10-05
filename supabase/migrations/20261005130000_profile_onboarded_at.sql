-- When a user finished onboarding: they have a handle AND at least 3 niches.
-- Null until then. Set once by the backend and never cleared.
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS onboarded_at timestamptz;
