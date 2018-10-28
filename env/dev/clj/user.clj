(ns user
  (:require [clojure.string :as string]
            [mount.core :as mount]
            [clj-time.core :as t]
            [clj-time.periodic :refer [periodic-seq]]
            [ephyra.figwheel :refer [start-fw stop-fw cljs]]
            [ephyra.db.core :as db]
            [ephyra.jobs.persistence :as persistence]
            [ephyra.fixtures :refer [consume-events! plan-invocation-events]]
            ephyra.core)
  (:import java.util.UUID))

(defn start []
  (mount/start-without #'ephyra.core/repl-server #'ephyra.core/http-healthcheck))

(defn stop []
  (mount/stop-except #'ephyra.core/repl-server #'ephyra.core/http-healthcheck))

(defn restart []
  (stop)
  (start))

(defn patched-sample-events
  "Sample events with the current date.

  We insert the records 3 min apart from each other, up to `max-timestamp`."
  [max-timestamp]
  (let [plans-events (vals plan-invocation-events)
        original-events (flatten plans-events)
        original-uuids (into #{} (filter some? (for [event original-events]
                                                 (or (get-in event [:body :uuid])
                                                     (get-in event [:body :run-plan-uuid])))))
        rewritten-uuids (zipmap original-uuids (repeatedly #(str (UUID/randomUUID))))
        ;; Rewrite UUIDs so the events can be inserted multiple times.
        ;; UUIDs must be kept identical in each of the events of a plan.
        ;; UUIDs of different plans must differ.
        plans-events-rewriten-uuids
        (for [plan-events plans-events]
          (for [event plan-events]
            (-> event
                (update-in [:body :uuid] rewritten-uuids)
                (update-in [:body :run-plan-uuid] rewritten-uuids))))
        events (flatten plans-events-rewriten-uuids)
        timestamps-decreasing (periodic-seq max-timestamp (t/minutes -3))
        events-timestamps (reverse (take (count events) timestamps-decreasing))]
    (for [[event timestamp] (map vector events events-timestamps)]
      (-> event
          (assoc-in [:body :started] timestamp)
          (assoc-in [:body :stopped] timestamp)))))

(defn load-sample-events 
  "Load sample events for days from `days-to-the-past` days ago to today."
  ([] (load-sample-events 50))
  ([days-to-the-past]
   (mount/start #'ephyra.config/env #'db/*db*)

   (doseq [timestamp (take days-to-the-past (periodic-seq (t/now) (t/days -1)))]
     (apply consume-events! (patched-sample-events timestamp)))))

(defn migrate []
  (mount/start #'ephyra.config/env)
  (ephyra.core/migratus ["migrate"]))

(defn rollback []
  (mount/start #'ephyra.config/env)
  (ephyra.core/migratus ["rollback"]))
