(ns ephyra.routes.home
  (:require [clojure.spec :as s]
            [ephyra.config :refer [get-setting]]
            [ephyra.encoding :as e]
            [ephyra.validation :as v]
            [ephyra.layout :as layout]
            [ephyra.jobs.persistence :as p]
            [ephyra.jobs.report :as report]
            [ephyra.frontend-paths :refer [frontend-paths]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-http.client :as client]
            [clj-statsd :as statsd]
            [compojure.core :refer [routes defroutes GET POST]]
            [ring.util.http-response :as response]
            [clojure.java.io :as io])
  (:import [java.net ConnectException]))

(defn validated-view [spec func]
  (fn [{params :params :as request}]
    (let [parsed-params (s/conform spec params)]
      (if (= parsed-params ::s/invalid)
        {:status 400 :body {:error (s/explain-str spec params)}}
        {:body (func parsed-params)}))))

(defn home-page []
  (layout/render "home.html"))

(s/def ::from ::e/timestamp)
(s/def ::to ::e/timestamp)
(s/def ::name string?)
(s/def ::state #{"all" "running" "failed" "lock-failed" "lost"})

(s/def ::main-report-params (s/keys :opt-un [::from ::to ::v/limit ::name ::state]))

(defn coerce-report-params [params]
  (let [today-beginning (t/floor (t/now) t/day)]
    (-> params
        (update :from #(or % today-beginning))
        (update :to #(or % (t/plus today-beginning (t/days 1))))
        (update :limit #(or % 100)))))

(def run-plans
  (validated-view
   ::main-report-params
   (fn [params]
     (let [params (coerce-report-params params)]
       {:tally (report/plans-tally params)
        :items (report/plan-invocations params)}))))

(def run-jobs
  (validated-view
   ::main-report-params
   (fn [params]
     (let [params (coerce-report-params params)]
       {:tally (report/jobs-tally params)
        :items (report/job-invocations params)}))))

(s/def ::failed-only (s/and (s/conformer
                          #(get {"true" true, "false" false, "" false} % ::s/invalid))))
(s/def ::job-history-params (s/keys :opt-un [::name ::failed-only ::v/limit]))
(s/def ::job-invocation-params (s/keys :opt-un [::name ::p/uuid]))

(def job-history (validated-view ::job-history-params report/job-history))
(def job-invocation (validated-view ::job-invocation-params report/job-invocation))

(defn call-executor [url]
  (let [{:keys [status body] :as executor-response}
        (statsd/with-timing "ephyra.executor-call.time"
          (try
            (-> (client/post url {:throw-exceptions false})
                (select-keys [:status :body]))
            (catch ConnectException e
              {:status 502 :body "Cannot reach the executor"})))]
    (statsd/increment "ephyra.executor-call.status-code" 1 1.0 [(str "code:" status)])
    (if (<= 200 status 299)
      executor-response
      (assoc executor-response :body {:executor-error body}))))

(defn rerun-plan
  "Request executor to re-run a plan via its HTTP RPC interface."
  [plan-name]
  (call-executor (str (get-setting :executor-run-plan-url) plan-name)))

(defn rerun-job
  "Request executor to re-run a job (as a part of a plan) via its HTTP RPC interface."
  [job-name plan-id]
  (call-executor (str (get-setting :executor-run-job-url) job-name "/" plan-id)))


(defroutes backend-routes
  (GET "/ping" [] "PONG")
  (GET "/run-plans/" req (run-plans req))
  (GET "/run-jobs/" req (run-jobs req))
  (GET "/job-history/" req (job-history req))
  (GET "/job-invocation/" req (job-invocation req))
  (POST "/rerun-plan/:plan-name" [plan-name] (rerun-plan plan-name))
  (POST "/rerun-job/:job-name/:plan-id" [job-name plan-id] (rerun-job job-name plan-id)))

(def frontend-routes
  (apply routes
         (map #(GET % [] (home-page))
              frontend-paths)))

(def home-routes (routes backend-routes frontend-routes))
