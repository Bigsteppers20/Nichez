-- Grants for the backend role on the tables that were created before it had any.
--
-- phone_challenges, phone_otp_sends and audit_events were written for a backend that connects as the
-- database owner. The backend is meant to move to the least-privilege role api_service (docs/SETUP.md),
-- and every feature added since writes audit_events, so the role needs exactly what the code does here:
--   phone_challenges  read, create, and update attempts and lockouts (never deleted)
--   phone_otp_sends   read and create (the send log behind the rate limits; never changed)
--   audit_events      read and create (a trigger already refuses update and delete)
-- RLS is forced and nothing is granted to anon or authenticated, as for every other table.

SELECT public.grant_api_service('public.phone_challenges', ARRAY['select', 'insert', 'update']);
SELECT public.grant_api_service('public.phone_otp_sends',  ARRAY['select', 'insert']);
SELECT public.grant_api_service('public.audit_events',     ARRAY['select', 'insert']);
