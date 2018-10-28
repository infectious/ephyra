ALTER TABLE run_job ADD COLUMN output TEXT;

UPDATE run_job SET output = (
    SELECT string_agg(output, '' order by chunk_no) FROM run_job_output_chunk
    WHERE run_job_id = run_job.id
);

DROP TABLE run_job_output_chunk;

DROP INDEX run_job__name;

ALTER TABLE run_job DROP CONSTRAINT run_job_pkey;
ALTER TABLE run_job DROP CONSTRAINT run_plan__run_plan_uuid_name;
ALTER TABLE run_job ADD CONSTRAINT run_job_pkey PRIMARY KEY(run_plan_uuid, name);
ALTER TABLE run_job DROP COLUMN id;

ALTER TABLE run_job DROP COLUMN output_length;
