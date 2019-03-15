(ns ephyra.jobs.consumer
  "Consuming plan (DAG) and job invocations, completions, and output."
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as string]
    [clojure.spec.alpha :as s]
    [gregor.core :as gregor]
    [mount.core :as mount :refer [defstate]]
    [cheshire.core :refer [parse-string]]
    [clj-statsd :as statsd]
    [ephyra.config :refer [get-setting]]
    [ephyra.spec :refer [must-conform]]
    [ephyra.jobs.persistence :refer [persist-plan-invocation! persist-job-invocation!
                                     persist-job-output! persist-job-completion!
                                     persist-lock-acquisition-failure!
                                     mark-lost-jobs!]]
    [slingshot.slingshot :refer [try+]])
  (:import java.sql.SQLException))


(defstate ^{:on-reload :noop}
  consumer
  :start (gregor/consumer (get-setting :kafka-server)
                          "ephyra"
                          [(get-setting :kafka-topic)]
                          {"auto.offset.reset" "earliest"
                           "enable.auto.commit" "false"})
  :stop (gregor/close consumer))

(defn consumer-metrics
  "Reads Kafka consumer metrics into a map."
  [consumer]
  (into {}
        (for [[key val] (.metrics consumer)]
          [(keyword (.name key)) (.value val)])))

(def persist-fn
  {"plan-invocation" persist-plan-invocation!
   "job-invocation" persist-job-invocation!
   "job-output" persist-job-output!
   "job-completion" persist-job-completion!
   "lock-acquisition-failure" persist-lock-acquisition-failure!
   "active-jobs" mark-lost-jobs!})

(s/def ::event persist-fn)

(defn persist-event! [{:keys [body event]}]
  (let [db-fun (persist-fn (must-conform ::event event))]
    (statsd/with-timing (str "ephyra.consumer.persist-event." event)
      (db-fun body))))

(defn clojurify-key [k]
  (if (string? k)
    (-> k
        (string/replace "_" "-")
        (keyword))
    k))

(defn records-seq
  "Returns a lazy seq of concatenated results of consecutive polls."
  [consumer]
  (mapcat identity (gregor/records consumer)))

(defn persist-record! [record]
  (let [offset (:offset record)
        msg (parse-string (:value record) clojurify-key)]
    (try+
      (persist-event! msg)
      (catch [:type ::s/invalid] {:keys [explanation]}
        (statsd/increment "ephyra.consumer.nonconforming")
        (log/error "Message on offset" offset "does not conform to the spec:" explanation " message " msg))
      (catch SQLException e
        (statsd/increment "ephyra.consumer.sqlexception")
        (log/error e "Failed to persist an event to the DB. Offset: " offset " message " msg))
      (else
        (statsd/increment "ephyra.consumer.ingested")
        (log/debug "Ingested message" msg)))))

(defn listen []
  (doseq [record (records-seq consumer)]
    (persist-record! record)
    (gregor/commit-offsets! consumer)))
