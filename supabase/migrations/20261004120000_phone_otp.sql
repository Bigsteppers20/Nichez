-- Phone OTP sign-in: server-side challenge state, send log for rate limits, audit trail.
--
-- These tables are written only by the Spring Boot backend (direct Postgres
-- connection). RLS is on with NO policies and no grants to the Data API roles,
-- so PostgREST (anon / authenticated) can neither read nor write them.

-- One row per OTP challenge. The OTP itself is never stored: Supabase Auth
-- generates, sends and checks the code. We only track attempts and lockouts.
CREATE TABLE public.phone_challenges (
    id            uuid        PRIMARY KEY,
    phone_e164    text        NOT NULL CHECK (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    created_at    timestamptz NOT NULL,
    last_sent_at  timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    locked_until  timestamptz,
    verified_at   timestamptz,
    version       bigint      NOT NULL DEFAULT 0
);

-- Lockout lookup on /start and /resend: any lock on this phone, newest first.
CREATE INDEX phone_challenges_phone_locked_idx
    ON public.phone_challenges (phone_e164, locked_until)
    WHERE locked_until IS NOT NULL;

-- One row per SMS requested (start and resend). Counted per phone and per IP
-- over a sliding hour. A provider failure still counts: it is the conservative choice.
CREATE TABLE public.phone_otp_sends (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    challenge_id  uuid        NOT NULL REFERENCES public.phone_challenges (id) ON DELETE CASCADE,
    phone_e164    text        NOT NULL,
    ip_address    text        NOT NULL,
    sent_at       timestamptz NOT NULL
);

CREATE INDEX phone_otp_sends_phone_idx ON public.phone_otp_sends (phone_e164, sent_at);
CREATE INDEX phone_otp_sends_ip_idx    ON public.phone_otp_sends (ip_address, sent_at);

-- Append-only security audit trail. Phones are stored masked, never in full.
CREATE TABLE public.audit_events (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type  text        NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]*$'),
    ip_address  text,
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL
);

CREATE INDEX audit_events_type_created_idx ON public.audit_events (event_type, created_at);

CREATE FUNCTION public.audit_events_append_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog'
AS $function$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$function$;

CREATE TRIGGER audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON public.audit_events
    FOR EACH ROW EXECUTE FUNCTION public.audit_events_append_only();

ALTER TABLE public.phone_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.phone_otp_sends  ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_events     ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.phone_challenges, public.phone_otp_sends, public.audit_events
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.audit_events_append_only() FROM PUBLIC, anon, authenticated;
