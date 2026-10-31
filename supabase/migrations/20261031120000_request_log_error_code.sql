-- Why a request failed: the stable `code` of the error body (TOKEN_EXPIRED, VALIDATION_FAILED,
-- HANDLE_TAKEN, INTERNAL_ERROR, ...), the same one the app receives. Null for a success.
--
-- Only the code, never the detail text: codes are a fixed list (docs/API_ERRORS.md), so nothing a
-- caller typed can end up here. For a 500 the stack trace is in the backend's own log (on Render:
-- the service's Logs tab), found by request_id.

ALTER TABLE public.request_logs
    ADD COLUMN error_code text CHECK (char_length(error_code) <= 64);

CREATE INDEX request_logs_failures_idx ON public.request_logs (created_at DESC) WHERE status >= 400;
