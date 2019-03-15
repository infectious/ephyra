(ns ephyra.routes.health
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [mount.core :as mount]
            [ephyra.config :refer [get-setting]]
            [ephyra.db.core :refer [*db*]]
            [ephyra.jobs.consumer :refer [consumer consumer-metrics]]
            [ephyra.jobs.report :as report]
            [compojure.core :refer [defroutes GET]])
  (:import [java.net ConnectException]))

(defn state-started? [state-var]
  (contains? (mount/running-states) (str state-var)))

(defn check-db []
  (try
    (report/plan-invocations {:from (t/now) :to (t/minus (t/now) (t/days 1))
                              :limit 10 :name nil})
    true
    (catch Exception e
      (log/error e "Exception when pinging the DB")
      false)))

(defn check-consumer []
  (let [heartbeat-sec-ago (:last-heartbeat-seconds-ago (consumer-metrics consumer))
        max-hearbeat-interval (get-setting :max-hearbeat-interval)]
    (< heartbeat-sec-ago max-hearbeat-interval)))

(defn check-health
  "Runs healthchecks. If the consumer is not started, doesn't check it."
  []
  (let [db-started (state-started? #'*db*)
        consumer-started (state-started? #'consumer)
        db-ok (and db-started (check-db))
        consumer-ok (when consumer-started (check-consumer))] ;; Check consumer only if started.
    ;; nil accepted - means not started.
    {:status (if (or (false? db-ok) (false? consumer-ok)) 500 200)
     :body {:db db-ok :consumer consumer-ok}}))

(defroutes health-routes
  (GET "/ping" [] (check-health)))
