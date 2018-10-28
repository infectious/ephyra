(ns ephyra.async
  (:require [cljs.core.async :refer [alt! put! go-loop chan timeout]]
            [ajax.core :refer [GET]]
            [cljs-time.core :as t]
            [cljs-time.coerce :refer [to-local-date-time]]
            [re-frame.core :as rf]
            [ephyra.constants :refer [refresh-interval]]
            [ephyra.conversions :refer [to-backend-date]]))

(defonce request-chan (chan))

(defn normalise-filtering-params [{:keys [date-from date-to name state limit]}]
  (let [params {:from (to-backend-date (to-local-date-time date-from))
                :name name
                :state state
                :limit limit}]
    (if date-to
      (assoc params :to (to-backend-date (t/plus (to-local-date-time date-to)
                                                 (t/days 1))))
      params)))

(defn GET-to-chan [path params]
  "Performs a GET request and puts the resp or an error to the returned channel."
  (let [resp-chan (chan)]
    (GET path
         :params params
         :handler #(put! resp-chan {:result % :error nil})
         :error-handler
         (fn [err]
           (put! resp-chan
                 {:error (assoc (select-keys err [:status :status-text :response]) :params params)})))
    resp-chan))

(defn query-plans-report
  [params]
  (GET-to-chan "/run-plans/" (normalise-filtering-params params)))

(defn query-jobs-report
  [params]
  (GET-to-chan "/run-jobs/" (normalise-filtering-params params)))

(defn query-job-history [params]
  (GET-to-chan "/job-history/" params))

(defn query-job-invocation [params]
  (GET-to-chan "/job-invocation/" params))

(def resource-fns
  {:plans-report query-plans-report
   :jobs-report query-jobs-report
   :job-history query-job-history
   :job-invocation query-job-invocation})

(defn run-refresh-query-loop!
  "Run a go-loop handling refreshes and discards.

  This go-loop will only pick up the most recent event and discard the older ones.
  So e.g. filtering updates will override a pending refresh response.
  "
  []
  (go-loop
    [resource :plans-report
     response-chan (chan)
     params {}
     refresh-chan (chan)]
    (alt!
      response-chan
      ([resp] ; Got a response - update the right view and refresh after some time.
       (do
         (rf/dispatch [:fetching (if (:result resp) :ok :err)])
         (rf/dispatch [(case resource
                         :plans-report :set-plans-report
                         :jobs-report :set-jobs-report
                         :job-history :set-job-history
                         :job-invocation :set-job-invocation) resp])
         (recur resource (chan) params (timeout refresh-interval))))

      request-chan
      ([[resource params]]  ; Query, disable refresh
       (rf/dispatch [:fetching :started])
       (recur resource ((resource-fns resource) params) params (chan)))

      refresh-chan  ; This channel may resolve or not, depending what the previous iteration passed.
      ; Requery, disable refresh
      (recur resource ((resource-fns resource) params) params (chan)))))
