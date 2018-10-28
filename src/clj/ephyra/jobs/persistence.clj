(ns ephyra.jobs.persistence
  "Persisting job/plan invocations, completions, and output."
  (:require [ephyra.db.core :as db]
            [ephyra.encoding :as e]
            [ephyra.spec :refer [must-conform]]
            [clj-time.format :as f]
            [clj-statsd :as statsd]
            [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec :as s]
            [clojure.spec.gen :as sgen]
            [clojure.spec.test :as stest]
            [clojure.string :as string]
            [conman.core :as conman])
  (:import [org.joda.time.DateTime]))


(defn gen-job-name []
  (sgen/fmap
    (comp
      #(string/join "" (interleave (cycle ["" "" "" ""  \_]) %))  ; Add underscores to the name.
      string/lower-case)
    (sgen/string-alphanumeric)))

(defn gen-uuid []
  (sgen/fmap str (sgen/uuid)))

(defn make-gen-reasonably-small-non-negative-int [n]
  (fn gen-reasonably-small-pos-int []
    (sgen/choose 0 n)))

(s/def ::non-empty-str (s/and string? not-empty))
(s/def ::non-neg-int (s/and integer? #(<= 0 %)))
(s/def ::non-neg-num (s/and number? #(<= 0 %)))
(s/def ::uuid
  (s/spec (s/and ::non-empty-str
                 (s/conformer (fn [s] (try (java.util.UUID/fromString s)
                                           (catch IllegalArgumentException e ::s/invalid)))
                              identity))
          :gen gen-uuid))
(s/def ::root-job-name ::non-empty-str)
(s/def ::version ::non-empty-str)
(s/def ::name ; Names are acually #"^[a-z][a-z0-9_]+$" but we shouldn't be that opinionated here.
  (s/spec string? :gen gen-job-name))
(s/def ::retries
  (s/spec ::non-neg-int
          :gen (make-gen-reasonably-small-non-negative-int 5)))
(s/def ::depends-on
  ; coll-of alone would be enough for the spec but the generator recurses infinitely if it doesn't
  ; have an explicit terminating branch to pick (in this case, nil)
  (s/nilable (s/coll-of ::job :gen-max 2)))

(s/def ::invocation-tuple (s/tuple ::name ::retries))
(s/def ::link (s/coll-of ::non-neg-int :count 2 :distinct true))
(s/def ::nodes (s/+ ::invocation-tuple))
(s/def ::links (s/* ::link))
(s/def ::graph (s/keys :req-un [::nodes ::links]))

(s/def ::started ::e/timestamp)
(s/def ::stopped ::e/timestamp)
(s/def ::hhmm
  (s/spec
    (s/and
      string?
      (fn [hh-mm]
        (try
          (f/parse (f/formatter :hour-minute) hh-mm)
          (catch IllegalArgumentException e false))))
    :gen (fn []
           (sgen/fmap
             #(f/unparse (f/formatter :hour-minute) %)
             (e/gen-timestamp)))))

(s/def ::start-time (s/nilable ::hhmm))
(s/def ::stop-time (s/nilable ::hhmm))
(s/def ::interval
  (s/spec (s/nilable ::non-neg-num)
          :gen (make-gen-reasonably-small-non-negative-int 200)))
(s/def ::output string?)
(s/def ::success (s/or :true true? :false false?))
(s/def ::run-plan-uuid ::uuid)
(s/def ::outcome #{"success" "failure" "partial-failure"})

(s/def ::job-identity (s/tuple ::uuid ::name))
(s/def ::jobs (s/coll-of ::job-identity))

(s/def ::plan-invocation (s/keys :req-un [::uuid ::root-job-name ::graph ::started ::version
                                          ::start-time ::stop-time ::interval]))
(s/def ::job-invocation (s/keys :req-un [::run-plan-uuid ::name ::started]))
(s/def ::job-output (s/keys :req-un [::run-plan-uuid ::name ::output]))
(s/def ::job-completion (s/keys :req-un [::run-plan-uuid ::name ::stopped ::output]
                                :opt-un [::outcome ::success]))
(s/def ::lock-acquisition-failure
  (s/keys :req-un [::run-plan-uuid ::name ::started ::attempt ::max-attempts]))
(s/def ::active-jobs (s/keys :req-un [::jobs]))


(defn persist-plan-invocation!
  "Inserts a plan invocation entry to the database."
  [plan-invocation]
  (->> plan-invocation
       (must-conform ::plan-invocation)
       (db/persist-plan-invocation)))

(defn persist-lock-acquisition-failure!
  "Inserts a job invocation entry to the database."
  [job-invocation]
  (->> job-invocation
       (must-conform ::lock-acquisition-failure)
       (db/persist-lock-acquisition-failure)))

(defn persist-job-invocation!
  "Inserts a job invocation entry to the database."
  [job-invocation]
  (->> job-invocation
       (must-conform ::job-invocation)
       (db/persist-job-invocation)))

(defn persist-job-output!
  "Inserts a job invocation entry to the database."
  [job-invocation]
  (->> job-invocation
       (must-conform ::job-output)
       (db/persist-job-output)))

(defn persist-job-completion!
  "Updates the matching job invocation entry in the database."
  [job-completion]
  (as-> job-completion m
    (must-conform ::job-completion m)
    (update m :outcome #(or % (if (:success m) "success" "failure")))
    (db/persist-job-completion m))
  ; The completion event may contain a message (backend errors are in-band, which is ugly -
  ; we should really be treating them as out-of-band):
  (db/persist-job-output (must-conform ::job-output job-completion)))

(defn mark-lost-jobs!
  "Finds 'running' jobs in the database which are not reported in the event and marks them
  as 'lost'."
  [running-jobs]
  (let [marked-as-lost (db/mark-lost-jobs (must-conform ::active-jobs running-jobs))]
    (statsd/increment "ephyra.consumer.marked-as-lost" (count marked-as-lost))
    (when (seq marked-as-lost)
      (log/warn "Jobs marked as lost:" marked-as-lost))))
