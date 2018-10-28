ALTER TABLE run_job DROP COLUMN lock_acquisition_failures;
ALTER TABLE run_job DROP COLUMN lock_acquisition_max_failures;


ALTER TABLE run_job ADD COLUMN success BOOLEAN;

UPDATE run_job SET success = CASE
    WHEN state = 'success' THEN true
    WHEN state = 'failed'  THEN false
    WHEN state = 'running' THEN NULL
END;

ALTER TABLE run_job DROP COLUMN state;
DROP TYPE JOB_STATE;
