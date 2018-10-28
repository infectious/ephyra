CREATE INDEX run_job__name ON run_job (name);

ALTER TABLE run_job DROP CONSTRAINT run_job_pkey;
ALTER TABLE run_job ADD CONSTRAINT run_plan__run_plan_uuid_name UNIQUE(run_plan_uuid, name);
ALTER TABLE run_job ADD COLUMN id SERIAL PRIMARY KEY;
ALTER TABLE run_job ADD COLUMN output_length INTEGER NOT NULL DEFAULT 0;

CREATE TABLE run_job_output_chunk (
    run_job_id INTEGER REFERENCES run_job (id) DEFERRABLE INITIALLY DEFERRED,
    chunk_no INTEGER,
    output TEXT NOT NULL,

    UNIQUE (run_job_id, chunk_no)
);
CREATE INDEX run_job_output_chunk_run_job_id ON run_job_output_chunk (run_job_id);


-- Truncate the current outputs to 1 000 000 chars and store them.
-- The chunks that we insert will be smaller but there's not much harm in having them as large
-- as 1M. We won't be able to "paginate" them if we implement "load more".
INSERT INTO run_job_output_chunk (run_job_id, chunk_no, output)
(
    SELECT id, 0, LEFT(run_job.output, 1000000)
    FROM run_job
    WHERE run_job.output IS NOT NULL
);

ALTER TABLE run_job DROP COLUMN output;
