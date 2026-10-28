-- API request log: one row per request under /api, with how long the answer took.
--
-- The backend writes these in small batches a few seconds after the request, so a slow or
-- unavailable database never slows an API call. It records who called, which route and how it
-- ended. It never records the query string, a request or response body, or any header (so no tokens,
-- phone numbers or search words). Rows are deleted after request-log.retention (30 days by default),
-- and user_id is cleared when the person's account is erased.
--
-- Read it in Supabase Studio or with SQL. request_log_hourly is the report: requests, errors and
-- response times per route per hour.

CREATE TABLE public.request_logs (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at   timestamptz NOT NULL,   -- when the request arrived
    request_id   text        NOT NULL,   -- the X-Request-Id of the response; the same id is in error bodies and backend logs
    method       text        NOT NULL CHECK (char_length(method) <= 16),
    route        text        NOT NULL,   -- the route pattern, e.g. /api/v1/posts/{id}; '(unmatched)' when none matched
    path         text        CHECK (char_length(path) <= 200),  -- the real path, kept only when no route matched (a 401 before routing, a 404)
    status       smallint    NOT NULL CHECK (status BETWEEN 100 AND 599),
    duration_ms  integer     NOT NULL CHECK (duration_ms >= 0),
    user_id      uuid        -- the signed-in caller; no foreign key, so a log row can never block anything
);

CREATE INDEX request_logs_created_idx ON public.request_logs (created_at DESC);
CREATE INDEX request_logs_request_idx ON public.request_logs (request_id);
CREATE INDEX request_logs_user_idx ON public.request_logs (user_id, created_at DESC) WHERE user_id IS NOT NULL;

-- select: reports. insert: the batch writer. delete: the retention purge. update: clearing user_id on erasure.
SELECT public.grant_api_service('public.request_logs', ARRAY['select', 'insert', 'update', 'delete']);

-- The report. A view runs with its owner's rights, so it is locked down like the table: only the
-- backend role and the database owner (Studio, SQL) can read it, never anon or authenticated.
CREATE VIEW public.request_log_hourly AS
SELECT date_trunc('hour', created_at)                                      AS hour,
       method,
       route,
       count(*)                                                            AS requests,
       count(*) FILTER (WHERE status >= 500)                               AS server_errors,
       count(*) FILTER (WHERE status >= 400 AND status < 500)              AS client_errors,
       round(avg(duration_ms))::integer                                    AS avg_ms,
       round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY duration_ms))::integer AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms))::integer AS p95_ms,
       max(duration_ms)                                                    AS max_ms
FROM public.request_logs
GROUP BY 1, 2, 3;

REVOKE ALL ON public.request_log_hourly FROM PUBLIC, anon, authenticated, api_service;
GRANT SELECT ON public.request_log_hourly TO api_service;

INSERT INTO public.service_tables (table_name, service_slug, introduced_in) VALUES
    ('request_logs', 'app-config', '20261027140000');
