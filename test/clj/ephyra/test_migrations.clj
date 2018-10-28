(ns ephyra.test-migrations
  "Forecast fetching and querying tests using the database."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [luminus-migrations.core :as migrations]
            [ephyra.fixtures :refer [db-setup app-env db-connect consume-events!
                                     plan-invocation-events report-plans]]
            [ephyra.db.core :as db]
            [ephyra.config :refer [get-setting]]
            [ephyra.jobs.report :as report])
  (:import [java.sql.BatchUpdateException]))


(deftest test-migrate-up-down-up
  (testing "Migrate up, load data, migrate down, check consistency"
    (let [prev-report (atom nil)
          prev-job-histories (atom nil)
          query-db (fn []
                     (let [report-results (report-plans)
                           jobs (map :root-job-name report-results)
                           job-histories (doall (map #(report/job-history
                                                        {:name % :failed-only false :limit 10})
                                                     jobs))]
                     [report-results job-histories]))]
      (db-setup  ; db-setup will migrate up and connect
                (fn []
                  (apply consume-events! (vals plan-invocation-events))
                  (let [[report job-histories] (query-db)]
                    (reset! prev-report report)
                    (reset! prev-job-histories job-histories))))
      (app-env
        (fn []
          ; migrate to the last migration with any schema
          (doseq [migration
                  ; Listing migrations latest to earliest (with compatible-enough schema to justify
                  ; testing). Sadly, it's impossible to roll back to a version.
                  ["20180615164321" "20180514135600" "20180502184611"
                   "20180323145249" "20180306151149" "20180226154229"]]
            (migrations/migrate ["rollback"]
                                {:database-url (get-setting :database-url)}))
          ; migrate back to the current one
          (migrations/migrate ["migrate"]
                              {:database-url (get-setting :database-url)})))
      (db-connect
        (fn []
          (let [[report job-histories] (query-db)
                ; Remove state variants introduced by migrations
                normalise-state #(cond
                                   (#{"lock_failed" "lock_waiting" "lost"} %) "running"
                                   (= "partial_failure" %) "failed"
                                   :else %)]
            (is (=
                 (for [plan @prev-report]
                   (-> plan
                       (update-in [:graph :nodes]
                                  (fn [nodes]
                                    (for [entry nodes]
                                      (-> entry
                                          (update :last-status normalise-state)))))
                       (update :previous-results
                               (fn [plan-invocation]
                                 (for [inv plan-invocation]
                                   (update inv :jobs (fn [jobs]
                                                       (for [job jobs]
                                                         (update job :state normalise-state)))))))))
                 report)
                "Rolling back and reapplying migrations should leave report results intact")
            (is (=
                 (for [job-history @prev-job-histories]
                   (for [job-entry job-history]
                     (-> job-entry
                         (assoc :lock-failures nil)
                         (update :state normalise-state))))
                 job-histories)
                "Rolling back and reapplying migrations should leave the job outputs intact")
            ))))))
