ALTER TABLE run_job ADD COLUMN lock_acquisition_failures INTEGER NULL;
ALTER TABLE run_job ADD COLUMN lock_acquisition_max_failures INTEGER NULL;

CREATE TYPE JOB_STATE AS ENUM ('lock_waiting', 'lock_failed', 'running', 'success', 'failed');
ALTER TABLE run_job ADD COLUMN state JOB_STATE;

UPDATE run_job SET state = CASE
    WHEN success = true  THEN 'success'::JOB_STATE
    WHEN success = false THEN 'failed'::JOB_STATE
    WHEN success IS NULL THEN 'running'::JOB_STATE
END;

ALTER TABLE run_job ALTER COLUMN state SET NOT NULL;

ALTER TABLE run_job DROP COLUMN success;
