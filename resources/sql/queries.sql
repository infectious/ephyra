-- :name persist-plan-invocation :!
-- :doc Inserts a plan invocation entry to the database.
INSERT INTO run_plan (uuid, root_job_name, graph, version, started, start_time, end_time, interval)
VALUES (:uuid::uuid, :root-job-name, :graph, :version, :started, :start-time, :stop-time, :interval);


-- :name persist-lock-acquisition-failure :!
-- :doc Persists lock acquisition failure info.
WITH t(state) AS (VALUES (
        (CASE WHEN :attempt = :max-attempts THEN 'lock_failed' ELSE 'lock_waiting' END)::JOB_STATE
))
INSERT INTO run_job
    (run_plan_uuid, name, started, lock_acquisition_failures, lock_acquisition_max_failures, state)
    VALUES (:run-plan-uuid::uuid, :name, :started, :attempt, :max-attempts, (SELECT state FROM t))
ON CONFLICT (run_plan_uuid, name)
DO UPDATE SET lock_acquisition_failures = :attempt, lock_acquisition_max_failures = :max-attempts,
    state = (SELECT state FROM t);


-- :name persist-job-invocation :!
-- :doc Inserts a job invocation entry to the database. job_invocation may get inserted twice in
--      case of manual reruns or previous lock acquisition failures.
INSERT INTO run_job (run_plan_uuid, name, started, state)
VALUES (:run-plan-uuid::uuid, :name, :started, 'running'::JOB_STATE)
ON CONFLICT (run_plan_uuid, name)
DO UPDATE SET name = :name, started = :started, state = 'running'::JOB_STATE;


-- :name persist-job-output :!
-- :doc Appends output to the job. The soft limit is up to 1 000 000 characters. Once it's
--      exceeded once, following insertions will have no effect.
WITH run_job (id, output_not_exceeded) AS (
    -- Finding the job and checking that we haven't exceeded the limit yet.
    SELECT id, output_length <= 1000000
    FROM run_job
    WHERE run_plan_uuid = :run-plan-uuid::uuid AND name = :name
), insertion (chunk_length) AS (
    -- Conditionally inserting another chunk,
    INSERT INTO run_job_output_chunk (run_job_id, chunk_no, output) (
        SELECT
            (SELECT id FROM run_job),
            (SELECT coalesce(max(chunk.chunk_no), -1) + 1 FROM run_job_output_chunk AS chunk
                WHERE chunk.run_job_id = (SELECT id FROM run_job)
            ) AS next_chunk_no,
            :output
        WHERE (SELECT output_not_exceeded FROM run_job)
    )
    RETURNING char_length(:output)
)
-- Updating the chunk length.
UPDATE run_job SET output_length = output_length + (SELECT chunk_length FROM insertion)
WHERE run_plan_uuid = :run-plan-uuid::uuid AND name = :name
    AND (SELECT output_not_exceeded FROM run_job);


-- :name persist-job-completion :!
-- :doc Persists information about job completion by updating a matching record.
UPDATE run_job SET stopped = :stopped,
    state = (CASE
        WHEN :outcome = 'success' THEN 'success'
        WHEN :outcome = 'failure' THEN 'failed'
        WHEN :outcome = 'partial-failure' THEN 'partial_failure'
    END)::JOB_STATE
WHERE run_plan_uuid = :run-plan-uuid::uuid AND name = :name;


-- :name mark-lost-jobs :<!
-- :doc Mark jobs other than the ones in the :jobs param as lost.
UPDATE run_job SET state = 'lost'::JOB_STATE
WHERE run_job.state IN ('running'::JOB_STATE, 'lock_waiting'::JOB_STATE)
    AND
/*~ (if-not (empty? (:jobs params)) */
    (run_job.run_plan_uuid, run_job.name) NOT IN (:tuple*:jobs)
/*~*/
    true
/*~ ) ~*/
RETURNING run_job.run_plan_uuid, run_job.name;


-- :name plan-invocations-report :n
-- :doc Select the latest run_plans in the period and glue the current and the previous
--  run_jobs, serialized to JSON - between :from and :to. Optional filtering of job names
--  by :name.
SELECT
    root_job_name AS "root-job-name",
    graph,
    started,
    version,
    start_time AS "start-time",
    end_time AS "stop-time",
    interval,
    (SELECT json_agg((json_build_object('name', name,
                                        'started', started, 'stopped', stopped,
                                        'state', state)))
        FROM run_job
        WHERE run_job.run_plan_uuid = recent_plans.uuid
    ) AS "latest-jobs",
    (SELECT json_agg(row_to_json(previous_results_rows))
        FROM
        (SELECT uuid, json_agg((json_build_object('state', run_job.state,
                                                  'name', run_job.name))
                               ORDER BY run_job.name) AS jobs
            FROM run_plan AS past_plans
            JOIN run_job ON run_job.run_plan_uuid = past_plans.uuid
            WHERE past_plans.root_job_name = recent_plans.root_job_name
                AND past_plans.started BETWEEN :from AND :to
            GROUP BY past_plans.uuid
            ORDER BY past_plans.started DESC
        ) AS previous_results_rows
    ) AS "previous-results"
FROM (
    SELECT DISTINCT ON (root_job_name)
        uuid,
        root_job_name,
        graph,
        started,
        version,
        start_time,
        end_time,
        interval
    FROM run_plan
    WHERE
/*~ (if (not= (:state params) "running") */
        started BETWEEN :from AND :to
/*~*/
        true
/*~ ) ~*/
        AND root_job_name IN (
            SELECT root_job_name
            FROM run_plan
            JOIN run_job ON run_job.run_plan_uuid = run_plan.uuid
            WHERE
            /*~ (if (not= (:state params) "running") */
                    run_plan.started BETWEEN :from AND :to
            /*~*/
                true
            /*~ ) ~*/
            /*~ (case (:state params)
                  "running" */
                AND state = 'running'::JOB_STATE
            /*~   "failed" */
                AND state IN ('failed'::JOB_STATE, 'partial_failure'::JOB_STATE)
            /*~   "lock-failed" */
                AND state IN ('lock_waiting'::JOB_STATE, 'lock_failed'::JOB_STATE)
            /*~   "lost" */
                AND state = 'lost'::JOB_STATE
            /*~   nil) ~*/
        )
/*~ (when-not (empty? (:name params)) */
        AND EXISTS (
            SELECT node_name
            FROM jsonb_array_elements_text(run_plan.graph -> 'nodes') AS node_name
            WHERE node_name LIKE '%' || :name || '%'
        )
/*~ ) ~*/
    ORDER BY root_job_name, started DESC
) AS recent_plans
ORDER BY recent_plans.started DESC, recent_plans.root_job_name
LIMIT :limit;


-- :name job-invocations-report :n
-- :doc Query a job report.
SELECT
    *,
    (SELECT json_agg((json_build_object('uuid', run_jobs_in_range.run_plan_uuid::VARCHAR,
                                        'state', run_jobs_in_range.state))
     ORDER BY run_jobs_in_range.started DESC)
     FROM run_job AS run_jobs_in_range
     WHERE run_jobs_in_range.name = recent_jobs.name
         AND run_jobs_in_range.started BETWEEN :from AND :to
    ) AS "previous-results"
FROM (
    SELECT DISTINCT ON (name)
        run_plan.version,
        run_job.run_plan_uuid, run_job.name, run_job.started, run_job.stopped, run_job.state,
        lock_acquisition_failures::VARCHAR || '/' || lock_acquisition_max_failures::VARCHAR AS "lock-failures"
    FROM run_job
    JOIN run_plan ON run_job.run_plan_uuid = run_plan.uuid
    WHERE name IN (
        SELECT DISTINCT name
        FROM run_job
        WHERE
        /*~ (if (not= (:state params) "running") */
            run_job.started BETWEEN :from AND :to
        /*~*/
            true
        /*~ ) ~*/
        /*~ (when-not (empty? (:name params)) */
            AND name LIKE '%' || :name || '%'
        /*~ ) ~*/
        /*~ (case (:state params)
              "running" */
            AND state = 'running'::JOB_STATE
        /*~   "failed" */
            AND state IN ('failed'::JOB_STATE, 'partial_failure'::JOB_STATE)
        /*~   "lock-failed" */
            AND state IN ('lock_waiting'::JOB_STATE, 'lock_failed'::JOB_STATE)
        /*~   "lost" */
            AND state = 'lost'::JOB_STATE
        /*~   nil) ~*/
        )
    ORDER BY name, run_job.started DESC
    ) AS recent_jobs
ORDER BY started DESC, name
LIMIT :limit;


-- :name job-history :n
-- :doc Query history of a single job.
SELECT
    run_plan.version,
    run_job.run_plan_uuid, run_job.name, run_job.started, run_job.stopped, run_job.state,
    lock_acquisition_failures::VARCHAR || '/' || lock_acquisition_max_failures::VARCHAR AS "lock-failures",
    (SELECT LEFT(chunk.output, 100)
     FROM run_job_output_chunk AS chunk
     WHERE chunk.run_job_id = id
     ORDER BY chunk.chunk_no
     LIMIT 1) AS output,
    output_length AS "output-length"
FROM run_job
JOIN run_plan ON run_job.run_plan_uuid = run_plan.uuid
WHERE run_job.name = :name
/*~ (when (:failed-only params) */
      AND run_job.state in ('failed'::JOB_STATE, 'lock_failed'::JOB_STATE)
/*~ ) ~*/
ORDER BY run_job.started DESC
LIMIT :limit;


-- :name job-invocation :n
-- :doc Single job invocation with full output.
--      Sub-select faster than a JOIN in my testing.
SELECT
    run_plan.version,
    run_job.run_plan_uuid, run_job.name, run_job.started, run_job.stopped, run_job.state,
    lock_acquisition_failures::VARCHAR || '/' || lock_acquisition_max_failures::VARCHAR AS "lock-failures",
    (SELECT string_agg(chunk.output, '' order by chunk.chunk_no)
     FROM run_job_output_chunk AS chunk WHERE chunk.run_job_id = id) AS output,
    output_length AS "output-length"
FROM run_job
JOIN run_plan ON run_job.run_plan_uuid = run_plan.uuid
WHERE run_job.name = :name AND run_job.run_plan_uuid = :uuid::uuid;


-- :name jobs-tally :1
-- :doc Categorise jobs by state and sum distinct jobs in categories.
SELECT
    COUNT(DISTINCT name) AS "all",
    -- Count all running jobs, not only the ones in range.
    (SELECT COUNT(DISTINCT name) FROM run_job
        WHERE state = 'running'::JOB_STATE
/*~ (when-not (empty? (:name params)) */
        AND run_job.name LIKE '%' || :name || '%'
/*~ ) ~*/
    ) AS "running",
    COUNT(DISTINCT name) FILTER (WHERE state IN ('failed'::JOB_STATE,
                                                 'partial_failure'::JOB_STATE)) AS "failed",
    COUNT(DISTINCT name) FILTER (WHERE state IN ('lock_waiting'::JOB_STATE,
                                                 'lock_failed'::JOB_STATE)) AS "lock-failed",
    COUNT(DISTINCT name) FILTER (WHERE state = 'lost'::JOB_STATE) AS "lost"
FROM run_job
WHERE run_job.started BETWEEN :from AND :to
/*~ (when-not (empty? (:name params)) */
    AND run_job.name LIKE '%' || :name || '%'
/*~ ) ~*/
;

-- :name plans-tally :1
-- :doc Categorise plans by job state and sum distinct plans in categories.
WITH plan_jobs (root_job_name, states) AS (
    SELECT
        root_job_name,
        array_agg(DISTINCT run_job.state)
    FROM run_plan
    JOIN run_job ON run_job.run_plan_uuid = run_plan.uuid
    WHERE run_plan.started BETWEEN :from AND :to
/*~ (when-not (empty? (:name params)) */
        AND EXISTS (
            SELECT node_name
            FROM jsonb_array_elements_text(run_plan.graph -> 'nodes') AS node_name
            WHERE node_name LIKE '%' || :name || '%'
        )
/*~ ) ~*/
    GROUP BY run_plan.root_job_name
)
SELECT
    COUNT(DISTINCT root_job_name) AS "all",

    -- Count all running plans, not only the ones in range.
    (SELECT COUNT(DISTINCT root_job_name)
     FROM run_plan JOIN run_job ON run_plan.uuid = run_job.run_plan_uuid
     WHERE run_job.state = 'running'::JOB_STATE
/*~ (when-not (empty? (:name params)) */
         AND run_job.name LIKE '%' || :name || '%'
/*~ ) ~*/
    ) AS "running",

    COUNT(DISTINCT root_job_name) FILTER (
        WHERE 'failed'::JOB_STATE = ANY (states) OR
              'partial_failure'::JOB_STATE = ANY (states)
    ) AS "failed",

    COUNT(DISTINCT root_job_name) FILTER (
        WHERE 'lock_waiting'::JOB_STATE = ANY (states) OR
              'lock_failed'::JOB_STATE = ANY (states)
    ) AS "lock-failed",

    COUNT(DISTINCT root_job_name) FILTER (WHERE 'lost'::JOB_STATE = ANY (states)) AS "lost"
FROM plan_jobs;
