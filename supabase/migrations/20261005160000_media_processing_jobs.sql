-- The media processing queue: one row per media handed to processing when its post is created.
--
-- Written in the same transaction as the post, so a post is never left "processing" with no job
-- behind it. The consumer (transcode, poster frame, publish) picks rows up by status and
-- available_at; available_at also serves as the retry back-off.

CREATE TABLE public.media_processing_jobs (
    media_id      uuid        PRIMARY KEY REFERENCES public.media (id),
    post_id       uuid        NOT NULL REFERENCES public.posts (id),
    status        text        NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued', 'running', 'done', 'failed')),
    attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at  timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- The consumer's scan: queued jobs that are due, oldest first.
CREATE INDEX media_processing_jobs_due_idx ON public.media_processing_jobs (available_at)
    WHERE status = 'queued';

CREATE TRIGGER media_processing_jobs_set_updated_at
    BEFORE UPDATE ON public.media_processing_jobs
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Jobs are never deleted: a finished job stays as the record of what was processed.
SELECT public.grant_api_service('public.media_processing_jobs', ARRAY['select', 'insert', 'update']);
