(ns ephyra.encoding
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clj-time.core :as t]
            [clj-time.format :as f]))


(def timestamp-ui (f/formatter "YYYY-MM-dd HH:mm:ss z"))

(defn parse-and-dont-be-stupid [dt-str]
  (if-let [parsed (f/parse dt-str)]
    parsed
    (throw (IllegalArgumentException.
             (str "No parser found for string " dt-str)))))

(defn timestamp-conformer
  "Validate the timestamp and coerce it to date-time if it's a string."
  [v]
  (cond
    (instance? org.joda.time.DateTime v) v
    (string? v) (try
                  (parse-and-dont-be-stupid v)
                  (catch IllegalArgumentException e ::s/invalid))))

(defn gen-timestamp []
  (let [minutes-in-day (* 24 60)
        gen-date-span-days 10]
    (gen/fmap
      #(->> (t/minutes %)
            (t/minus (t/now)))
      (gen/choose 0 (* minutes-in-day gen-date-span-days)))))

(s/def ::timestamp
  (s/spec (s/conformer timestamp-conformer identity)
          :gen gen-timestamp))
