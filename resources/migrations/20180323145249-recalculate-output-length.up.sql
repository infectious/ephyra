UPDATE run_job SET output_length = (
    SELECT COALESCE(sum(char_length(run_job_output_chunk.output)), 0)
    FROM run_job_output_chunk
    WHERE run_job_output_chunk.run_job_id = run_job.id
);
