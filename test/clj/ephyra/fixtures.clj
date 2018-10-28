(ns ephyra.fixtures
  "Fixtures and testing data"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.java.io :refer [resource reader]]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [cheshire.core :refer [parse-string]]
            [mount.core :as mount]
            [luminus-migrations.core :as migrations]
            [clj-time.core :as t]
            [ephyra.config :refer [get-setting]]
            [ephyra.jobs.report :as report]
            [ephyra.jobs.consumer :refer [clojurify-key persist-event!]]
            [ephyra.db.core :as db]))


; Testing resources

(defn load-sample-events
  "Loads the pointed resource as a file containing JSON lines."
  [res]
  (map #(parse-string % clojurify-key)
       (line-seq (reader (resource (str "samples/events/" res))))))


(def days (zipmap (range 1 5)
                  (iterate #(t/plus % (t/days 1))
                           (t/date-time 2018 2 28 0 0))))

(def full-range {:from (days 1) :to (days 4) :limit 100 :name nil})

(defn report-plans [& {:as opts}] (report/plan-invocations (merge full-range opts)))
(defn report-jobs [& {:as opts}] (report/job-invocations (merge full-range opts)))

(def plan-invocation-events
  {:happy-single-job (load-sample-events "happy_single_job.json")
   :happy-single-job-day-later (load-sample-events "happy_single_job_day_later.json")
   :sad-single-job (load-sample-events "sad_single_job.json")
   :graph (load-sample-events "graph.json")
   :multiroot-graph (load-sample-events "multiroot_graph.json")
   :single-sleeping-job-blocked (load-sample-events "single_sleeping_job_blocked.json")
   :single-sleeping-job-locked-running (load-sample-events "single_sleeping_job_locked_running.json")
   :happy-single-job-running (load-sample-events "happy_single_job_running.json")
   :happy-single-job-lock-failed (load-sample-events "happy_single_job_lock_failed.json")
   :happy-single-job-lock-wait (load-sample-events "happy_single_job_lock_wait.json")
   :lost-jobs (load-sample-events "lost_jobs.json")
   :partially-failing-chain (load-sample-events "partially_failing_chain.json")
   })

(defn consume-events!
  "Save events to the DB."
  [& events-seqs]
  (doseq [e (flatten events-seqs)]
    (persist-event! e)))


; Fixtures

(defn query-table-names
  "Returns a sequence of table names in the DB."
  []
  (->> (jdbc/query
         db/*db*
         ; "SELECT tablename FROM pg_tables where tableowner = 'ephyra'" should be enough but
         ; for no good reason newer postgres:9.6 Docker images assign pg_catalog tables to 'ephyra'
         ; when run with POSTGRES_USER=ephyra. Which is a total stupidity because no Postgres
         ; server EVER will have system tables belonging to a user different from 'postgres'.
         "SELECT tablename FROM pg_tables where tableowner = 'ephyra' AND schemaname = 'public'")
       (map :tablename)))

(defn app-env [f]
  (mount/start #'ephyra.config/env)
  (prn (:database-url ephyra.config/env))
  (f)
  (mount/stop #'ephyra.config/env))

(def db-connect
  (compose-fixtures
    app-env
    (fn [f]
      (mount/start #'db/*db*)
      (f)
      (mount/stop #'db/*db*))))

(defn db-cleanup [f]
  (when-let [table-names (not-empty (query-table-names))]
    (jdbc/execute! db/*db*
                   (str "DROP TABLE " (string/join ", " table-names) " CASCADE"))
   (jdbc/execute! db/*db*  ; no easy way to list user-defined types.
                   (str "DROP TYPE IF EXISTS JOB_STATE")) )
  (f))

(defn db-migrate [f]
  (migrations/migrate ["migrate"] {:database-url (get-setting :database-url)})
  (f))

(def db-setup (join-fixtures [db-connect db-cleanup db-migrate]))

(defn transient-transaction
  "Wrap f in a transaction. Roll back after f finishes."
  [f]
  (db/with-transaction
    (f)
    (jdbc/execute! db/*db* "ROLLBACK")))

(defn clean-tables
  "Deletes everything from all tables on setup."
  [f]
  (when-let [table-names (not-empty (query-table-names))]
    (jdbc/execute! db/*db* (str "TRUNCATE " (string/join ", " table-names))))
  (f))
