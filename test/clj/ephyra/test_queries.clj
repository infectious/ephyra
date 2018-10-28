(ns ephyra.test-queries
  "Forecast fetching and querying tests using the database."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [clojure.string :as string]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :refer [to-sql-date]]
            [luminus-migrations.core :as migrations]
            [ephyra.fixtures :refer [db-setup transient-transaction consume-events!
                                     plan-invocation-events days
                                     report-plans report-jobs]]
            [ephyra.db.core :as db]
            [ephyra.config :refer [get-setting]]
            [ephyra.jobs.consumer :refer [persist-event!]]
            [ephyra.jobs.report :as report])
  (:import [java.sql.BatchUpdateException]))

(use-fixtures :once db-setup)
(use-fixtures :each transient-transaction)

(defn history [& {:as opts}]
  (report/job-history (merge {:name "happy_single_job" :failed-only false :limit 10} opts)))

(deftest test-load-events
  (testing "Event loading duplicates"
    (is (= 0 (count (report-plans))))
    (is (= 0 (count (report-jobs))))
    (is (= 0 (count (history))))

    (consume-events! (:happy-single-job plan-invocation-events))
    (is (= 1 (count (report-plans))))
    (is (= 1 (count (report-jobs))))
    (is (= 1 (count (history))))

    (consume-events! (:happy-single-job-day-later plan-invocation-events))
    (is (= 1 (count (report-plans)))
        "2 invocations of the same plan should get agregated in plans report")
    (is (= 1 (count (report-jobs)))
        "2 invocations of the same job should get agregated in job report")
    (is (= 2 (count (history))) "2 invocations should be visible in job history")

    (db/with-transaction
      (is (thrown? java.sql.BatchUpdateException
                   (consume-events! (first (:happy-single-job plan-invocation-events))))
          "Inserting the same plan invocation again should fail"))))

(deftest test-append-output
  (testing "Appending output"
    (let [[plan-invocation job-invocation] (take 2 (:happy-single-job plan-invocation-events))
          uuid (get-in plan-invocation [:body :uuid])
          output-event
          (fn [len]
            {:event "job-output"
             :body {:run-plan-uuid uuid
                    :name "happy_single_job"
                    :output (string/join "" (repeat len "o"))}})
          output-preview (comp :output first history)

          job-invocation-detail #(report/job-invocation {:name "happy_single_job" :uuid uuid})
          output (comp :output job-invocation-detail)
          chunks-count
          #(:count (first (jdbc/query db/*db* "SELECT count(*) FROM run_job_output_chunk")))
          expected-output-soft-limit 1000000]

      (consume-events! plan-invocation)
      (is (= 0 (count (history))))
      (consume-events! job-invocation)
      (is (= 1 (count (history))))

      (consume-events! (output-event 20))
      (is (= 20 (count (output-preview))))
      (is (= 20 (count (output))))
      (is (= 1 (chunks-count)))

      (consume-events! (output-event 20))
      (is (= 20 (count (output-preview))) "Output preview should only show the first chunk.")
      (is (= 40 (count (output))))
      (is (= 2 (chunks-count)))

      (consume-events! (output-event expected-output-soft-limit))
      (is (= (+ expected-output-soft-limit 40) (count (output))))
      (is (= 3 (chunks-count)))

      (consume-events! (output-event 1))
      (is (= (+ expected-output-soft-limit 40) (count (output)))
          "No further output should be accepted.")
      (is (= 3 (chunks-count)))
      )))

(deftest test-locked-jobs
  (let [history #(history :name "single_sleeping_job")
        job-lock-state #(select-keys (first (history)) [:state :lock-failures])]

    (testing "Consuming log acquisition failure events"
      (let [events (:single-sleeping-job-blocked plan-invocation-events)
            [plan-invocation job-events] ((juxt first rest) events)
            [first-acq-failure mid-acq-failures last-acq-failure]
            ((juxt first (comp butlast rest) last) job-events)]
        (is (= events (concat [plan-invocation first-acq-failure] mid-acq-failures [last-acq-failure]))
            "Test precondition that we partition the events properly")

        (consume-events! plan-invocation)
        (is (= () (history)))

        (consume-events! first-acq-failure)
        (is (= (job-lock-state) {:state "lock_waiting" :lock-failures "1/6"}))

        (doseq [event mid-acq-failures]
          (consume-events! event)
          (is (= (job-lock-state) {:state "lock_waiting"
                                   :lock-failures (str (:attempt (:body event)) "/6")})))
        (is (= (job-lock-state) {:state "lock_waiting" :lock-failures "5/6"}))

        (consume-events! last-acq-failure)
        (is (= (job-lock-state) {:state "lock_failed" :lock-failures "6/6"}))))

    (testing "Consuming log acquisition failure events, followed by a job invocation"
      (let [events (:single-sleeping-job-locked-running plan-invocation-events)
            failing-to-acq-lock (take-while #(not= (:event %) "job-invocation") events)
            lock-acquired (drop (count failing-to-acq-lock) events)]
        (is (= events (concat failing-to-acq-lock lock-acquired))
            "Precondtion that we're partitioning right")

        (consume-events! failing-to-acq-lock)
        (is (= (job-lock-state) {:state "lock_waiting" :lock-failures "2/6"}))
        (consume-events! lock-acquired)
        (is (= (job-lock-state) {:state "running" :lock-failures "2/6"}))))))


(deftest tally-invariants
  #_(testing "Job and plan tallies agree with job and plan reports as we consume events"
    (doseq [event (flatten (vals plan-invocation-events))]
      (persist-event! event)

      (doseq [[day-from day-to] [[1 1] [1 2] [1 3] [1 4]
                                 [4 4] [3 4] [2 4]]
              state [:all :running :failed :lock-failed]
              :let [params {:from (days day-from)
                            :to (days day-to)
                            :limit 10000
                            :state (name state)
                            :name ""}]]
        (is (= (count (report/plan-invocations params)) (state (report/plans-tally params))))
        (is (= (count (report/job-invocations params)) (state (report/jobs-tally params)))))))

  (testing "Name parameter"
    (consume-events! (:sad-single-job plan-invocation-events))
    (consume-events! (:happy-single-job-running plan-invocation-events))

    (let [params {:from (days 1)
                  :to (days 4)
                  :limit 10
                  :state :all
                  :name ""}]
      (is (= 2
             (count (report/plan-invocations params))
             (:all (report/jobs-tally params))
             (:all (report/plans-tally params))))
      (is (= 1
             (count (report/plan-invocations (assoc params :state "running")))
             (count (report/plan-invocations (assoc params
                                                    :state "running"
                                                    :name "happy_single_job_running")))
             (:running (report/jobs-tally params))
             (:running (report/jobs-tally (assoc params :name "happy_single_job_running")))
             (:running (report/plans-tally params))
             (:running (report/plans-tally (assoc params :name "happy_single_job_running")))))

      (is (= 1
             (count (report/plan-invocations (assoc params
                                                    :name "sad_single_job")))
             (:all (report/jobs-tally (assoc params :name "sad_single_job")))
             (:all (report/plans-tally (assoc params :name "sad_single_job")))))

      (is (= 0
             (count (report/plan-invocations (assoc params
                                                    :state "running"
                                                    :name "sad_single_job")))
             (:running (report/jobs-tally (assoc params :name "sad_single_job")))
             (:running (report/plans-tally (assoc params :name "sad_single_job")))))
      )))

(deftest lost-jobs
  (testing "Marking jobs as lost"
    (let [events (:lost-jobs plan-invocation-events)
          [two-jobs-events [only-one-is-actually-running come-on-nothing-is-actually-running]]
          ((juxt (partial drop-last 2) (partial take-last 2)) events)]
      (consume-events! two-jobs-events)
      (is (= 2
             (count (report-plans))
             (count (report-jobs))))
      (is (= 1
             (count (report-plans :state "running"))
             (count (report-jobs :state "running"))))
      (is (= 1
             (count (report-plans :state "lock-failed"))
             (count (report-jobs :state "lock-failed"))))
      (is (= 0
             (count (report-plans :state "lost"))
             (count (report-jobs :state "lost"))))

      (consume-events! only-one-is-actually-running)
      (is (= 2
             (count (report-plans))
             (count (report-jobs))))
      (is (= 1
             (count (report-plans :state "running"))
             (count (report-jobs :state "running"))))
      (is (= 0
             (count (report-plans :state "lock-failed"))
             (count (report-jobs :state "lock-failed")))
          "The locked job should get marked as lost")
      (is (= 1
             (count (report-plans :state "lost"))
             (count (report-jobs :state "lost"))))

      (consume-events! come-on-nothing-is-actually-running)
      (is (= 2
             (count (report-plans))
             (count (report-jobs))))
      (is (= 0
             (count (report-plans :state "running"))
             (count (report-jobs :state "running"))
             (count (report-plans :state "lock-failed"))
             (count (report-jobs :state "lock-failed"))))
      (is (= 2
             (count (report-plans :state "lost"))
             (count (report-jobs :state "lost"))))
      )))
