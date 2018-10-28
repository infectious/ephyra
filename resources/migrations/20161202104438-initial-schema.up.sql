CREATE TABLE run_plan (
    uuid UUID PRIMARY KEY,  -- UUID of the celery plan runner task invocation.
    root_job_name TEXT,
    graph JSONB NOT NULL,
    version TEXT NOT NULL,

    -- Scheduling
    -- Text in the 'HH:MM' format. Intentionally not using TIME because it's deprecated.
    start_time VARCHAR(5),
    end_time VARCHAR(5),
    interval INTEGER,

    started TIMESTAMPTZ NOT NULL
);
CREATE INDEX run_plan__root_job_name__started ON run_plan (root_job_name, started DESC);
CREATE INDEX run_plan__started ON run_plan (started DESC);


CREATE TABLE run_job (
    run_plan_uuid UUID NOT NULL REFERENCES run_plan(uuid) DEFERRABLE INITIALLY DEFERRED,

    name TEXT NOT NULL,
    started TIMESTAMPTZ NOT NULL,
    stopped TIMESTAMPTZ,
    output TEXT,
    success BOOLEAN,

    PRIMARY KEY(run_plan_uuid, name)
);
CREATE INDEX run_job__run_plan_uuid ON run_job (run_plan_uuid);
