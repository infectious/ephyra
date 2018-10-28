(ns ephyra.conversions
  (:require [clojure.string :as string]
            [cljs-time.core :as t]
            [cljs-time.format :as f]))

(def backend-formatter (f/formatters :date-time))
(def qs-formatter (f/formatters :date))

(defn to-qs-date [goog-date]
  (when goog-date (f/unparse qs-formatter goog-date)))

(defn from-qs-date [goog-date]
  (when-not (string/blank? goog-date)
    (f/parse qs-formatter goog-date)))

(defn to-backend-date [goog-date]
  (when goog-date (f/unparse backend-formatter goog-date)))
