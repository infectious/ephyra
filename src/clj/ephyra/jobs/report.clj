(ns ephyra.jobs.report
  "Recently invoked plans report query."
  (:require [ephyra.db.core :as db]
            [ephyra.encoding :refer [timestamp-ui]]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as string]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-statsd :as statsd]
            [conman.core :as conman])
  (:import [org.joda.time.DateTime]))


(defn format-date [dt]
  (when dt (f/unparse timestamp-ui dt)))

(defn reformat-date [pg-dt]
  (some->> pg-dt f/parse format-date))

(defn jobs->map [jobs-seq]
  (zipmap (map :name jobs-seq) jobs-seq))

(defn status [job-result]
  (:state job-result "not run"))

(defn rebuild-node [[job-name retries] latest-jobs-m]
  (let [latest-job-result (get latest-jobs-m job-name)]
    {:name job-name
     :last-status (status latest-job-result)
     :started (reformat-date (:started latest-job-result))
     :stopped (reformat-date (:stopped latest-job-result))
     :retries retries}))

(defn rebuild-report-row [row]
  (let [latest-jobs-map (jobs->map (:latest-jobs row))]
    (-> row
        (update :started format-date)
        (dissoc :latest-jobs)
        (update-in [:graph :nodes]
                   (fn [nodes] (map #(rebuild-node % latest-jobs-map) nodes))))))

(defn rewrite-dates [m]
  (->> m
       (map #(update % :started format-date))
       (map #(update % :stopped format-date))))

(defn plan-invocations [params]
  (statsd/with-timing "ephyra.report.plan-invocations"
    (->> (db/plan-invocations-report params)
         (map rebuild-report-row))))

(defn job-invocations [params]
  (statsd/with-timing "ephyra.report.job-invocations"
    (rewrite-dates (db/job-invocations-report params))))

(defn job-history [params]
  (statsd/with-timing "ephyra.report.job-history"
    (rewrite-dates (db/job-history params))))

(defn job-invocation [params]
  (statsd/with-timing "ephyra.report.job-invocation"
    (first (rewrite-dates (db/job-invocation params)))))

(defn plans-tally [params]
  (statsd/with-timing "ephyra.report.plans-tally"
    (first (db/plans-tally params))))

(defn jobs-tally [params]
  (statsd/with-timing "ephyra.report.jobs-tally"
    (first (db/jobs-tally params))))
