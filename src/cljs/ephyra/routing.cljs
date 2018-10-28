(ns ephyra.routing
  "Routing. Be wary with code autoreloading when editing this file - it tends to love itself when
  making significant changes, which is kind of expected since the state is kept in the HTML5
  history."
  (:require [clojure.string :as string]
            [bide.core :as router]
            [ephyra.frontend-paths :refer [frontend-routes]]
            [ephyra.conversions :refer [to-qs-date]]
            [pushy.core :as pushy]
            [re-frame.core :as re-frame]))

(def routes (router/router frontend-routes))

(defn parse-url [url]
  (.log js/console "Matching url:" url)
  (let [matched (router/match routes url)]
    (prn "Matched:" matched)
    matched))

(defn dispatch-route [[route route-params query-params]]
  (prn "Dispatching:" [route route-params query-params])
  (re-frame/dispatch [:route route route-params query-params]))

(def pushy-obj (pushy/pushy dispatch-route parse-url))

(defn start! []
  (pushy/start! pushy-obj))

(def history pushy-obj)

(defn set-token! [token]
  (pushy/set-token! history token ""))

(defn replace-token! [token]
  (pushy/replace-token! history token))

(defn get-token [] (pushy/get-token history))

(def url-for-raw (partial router/resolve routes))

(defn url-for-with-qs
  "url-for that populates the query string. Nil values not populated.

  To be called in components only."
  [route route-params filtering]
  (let [query-params (as-> filtering q
                       (update q :job-failed-only str)
                       (update q :date-to to-qs-date)
                       (update q :date-from to-qs-date)
                       (remove (fn [[k v]] (or (string/blank? v))) q)
                       (into (sorted-map) q))]
    (url-for-raw route route-params query-params)))
