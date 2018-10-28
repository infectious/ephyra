UPDATE run_job SET state = 'failed'::JOB_STATE WHERE state = 'partial_failure'::JOB_STATE;

ALTER TYPE JOB_STATE RENAME TO JOB_STATE_tmp;

CREATE TYPE JOB_STATE AS ENUM (
    'lock_waiting', 'lock_failed', 'running', 'success', 'failed', 'lost');
ALTER TABLE run_job ALTER COLUMN state TYPE JOB_STATE USING state::text::JOB_STATE;

DROP TYPE JOB_STATE_tmp;
