(ns ephyra.handlers
  (:require [clojure.string :as string]
            [clojure.set :refer [rename-keys]]
            [cljs.spec.alpha :as s]
            [cljs.core.async :refer [alt! put! go-loop chan timeout]]
            [cljs-time.core :as t]
            [cljs-time.coerce :refer [from-string to-date-time]]
            [ajax.core :refer [POST]]
            [re-frame.core :as rf]
            [re-frame-fx.dispatch]
            [ephyra.async :refer [request-chan]]
            [ephyra.constants :refer [notification-duration rerun-forced-delay
                                      default-job-history-limit default-plan-limit
                                      tabs-keys]]
            [ephyra.conversions :refer [from-qs-date]]
            [ephyra.conversions :refer [from-qs-date]]
            [ephyra.routing :refer [url-for-with-qs set-token!]]
            [ephyra.validation :as v]))


(defn delayed [ms f]
  #(js/setTimeout f ms))

(def focus-choices #{:plans :jobs})
(def tab-choices (apply hash-set tabs-keys))

(rf/reg-event-db
  :initialize
  (fn []
    {:query-params {} ; see :route handler
     :route nil
     :route-params {:focus :plans :tab :all} ; see :route handler
     :fetching? false
     :previous-fetch-failed? false
     ;;; Queue elements are maps with keys: :key (unique key), :text (string or hiccup),
     ;; :class - bootstrap CSS class:
     :updates-feed #queue []

     :states-tally {}
     :jobs-report-response nil ; {:error nil :run-plans nil}
     :plans-report-response nil ; {:error nil :run-plans nil}
     :job-history-response nil ; {:error nil :response nil}
     :job-invocation-response nil ; {:error nil :job-invocation nil}

     :requested-plan-reruns #{}  ; Set of plan names for which rerunning was requested.
     :requested-job-reruns #{}  ; Set of [job-name plan-uuid] for which rerunning was requested.

     :version-column-expanded false
     :plan-histories-expanded #{}  ; Set of root job names.
     }))

(rf/reg-event-fx
  :route
  (fn [{:keys [db]} [_ route raw-route-params raw-query-params]]
    ;; For now limits can be strings (they're query params anyway) this will have to change if we
    ;; implement "load more".
    (let [route (if (= route :landing) :main-report route)
          route-params (-> raw-route-params
                           (update :focus #(get focus-choices (keyword %) :plans))
                           (update :tab #(get tab-choices (keyword %) :all)))
          query-params
          (-> raw-query-params
              (update :limit #(if (s/valid? ::v/limit %) % default-plan-limit))
              (update :name #(or % ""))
              (update :date-from #(or (from-qs-date %) (t/today-at-midnight)))
              (update :date-to from-qs-date)
              (update :job-limit #(if (s/valid? ::v/limit %) % default-job-history-limit))
              (update :job-failed-only #(= (string/lower-case (or % "false")) "true")))
          query-params-stripped (if (= :main-report route)
                                  (dissoc query-params :job-limit :job-failed-only)
                                  query-params)
          tab-changed? (not= (get-in db [:route-params :tab]) (:tab route-params))
          focus-changed? (not= (get-in db [:route-params :focus]) (:focus route-params))]

      ;; In case we corrected some of the parameters, we want to navigate to them, so the
      ;; corrections apply in the query string.
      ;; If the corrected URL differs from the original one, this event handler will be triggered
      ;; again with the corrected routes. We don't want to query the requested resource twice,
      ;; so we apply a small debounce.
      (cond->
        {:db (assoc db
                    :route route
                    :route-params route-params
                    :query-params query-params-stripped)

         :navigate [route route-params query-params-stripped]
         :dispatch-debounce [{:id ::route-navigate
                              :timeout 20
                              :action :dispatch
                              :event (case route
                                       :main-report [:request-main-report]
                                       :job-history [:request-job-history]
                                       :job-invocation [:open-job-invocation])}]}

        tab-changed?
        (update :db #(assoc %
                            :jobs-report-response nil
                            :plans-report-response nil
                            :plan-histories-expanded #{}))

        (and (or tab-changed? focus-changed?) (= (:tab route-params) :running))
        (assoc :dispatch
               [:log-into-feed
                {:class "alert-info"
                 :text (str "Showing all running " (name (:focus route-params)) ". "
                            "Date range applies to historical results only.")}])
        ))))

(defn pikaday-date-as-utc
  "Pikaday returns a JS date (UTC datetime) of the start of the current local date. We want the
  start of the current UTC date."
  [js-date]
  (when js-date
    (let [utc-offset (.getTimezoneOffset (js/Date.))
          goog-dt (to-date-time js-date)]
      (t/minus goog-dt (t/minutes utc-offset)))))


(rf/reg-event-fx
  :update-filtering
  (fn [{:keys [db]} [_ filtering-updates]]
    (let [{:keys [date-from date-to] :as new-filtering} (merge (:query-params db) filtering-updates)
          date-from-changed (not= date-from (get-in db [:query-params :date-from]))
          date-to-changed (not= date-to (get-in db [:query-params :date-to]))
          dates-given (and (some? date-from) (some? date-to))
          ;; Date "culling" - ensure date-to does not exceed date-from.
          date-from-culled (if (and date-to-changed dates-given (<= date-to date-from))
                             date-to
                             date-from)
          date-to-culled (if (and date-from-changed dates-given (<= date-to date-from))
                           date-from
                           date-to)
          new-filtering-sanitised (assoc new-filtering
                                         :date-from (pikaday-date-as-utc date-from-culled)
                                         :date-to (pikaday-date-as-utc date-to-culled))]
      ;; Navigation will set the state but we also have to to set the state so input can be typed
      ;; fluently.
      {:db (assoc db :query-params new-filtering-sanitised)})))

(rf/reg-event-fx
  :apply-filtering
  (fn [{:keys [db]} _]
    {:dispatch [:navigate :main-report (:route-params db) (:query-params db)]}))

(rf/reg-event-fx
  :navigate
  (fn [_ [_ route route-params filtering]]
    {:navigate [route route-params filtering]}))

(rf/reg-fx
  :navigate
  (fn [[route route-params filtering]]
    (set-token! (url-for-with-qs route route-params filtering))))

(rf/reg-event-fx
  :request-main-report
  (fn [{:keys [db]} _]
    {; Forget responses for neste views' queries:
     :db (assoc db
                ; Closing nested views.
                :job-history-response nil :job-invocation-response nil)
     :query-main-report [(get-in db [:route-params :focus])
                         (assoc (:query-params db) :state
                                (name (get-in db [:route-params :tab])))]}))

(rf/reg-fx
  :query-main-report
  (fn [[focus filtering]]
    (let [resource (case focus
                     :plans :plans-report
                     :jobs :jobs-report)]
      (put! request-chan [resource filtering]))))

(rf/reg-fx
  :query-jobs-report
  (fn [filtering]
    (put! request-chan [:jobs-report filtering])))

(rf/reg-event-fx
  :log-into-feed
  (fn [{:keys [db]} [_ & messages]]
    {:db (update db :updates-feed into (map #(assoc % :key (gensym "msg")) messages))
     :dispatch-later [{:ms notification-duration
                       :dispatch [:remove-from-feed (count messages)]}]}))

(rf/reg-event-db
  :remove-from-feed
  (fn [db [_ n]]
    (update db :updates-feed #(nth (iterate pop %) n))))

(rf/reg-event-fx
  :fetching
  (fn [{:keys [db]} [_ status]]
    (cond->
      {:db (assoc db :fetching? (= :started status))}

      (#{:ok :err} status)
      (assoc-in [:db :previous-fetch-failed?] (= :err status))

      (= :err status)
      (assoc :dispatch
             [:log-into-feed
              {:class "alert-danger" :text "Failed to refresh."}])

      (and (= :ok status) (:previous-fetch-failed? db))
      (assoc :dispatch
             [:log-into-feed
              {:class "alert-success" :text "OK, we're good - managed to refresh."}]))))

(rf/reg-event-db
  :set-plans-report
  (fn [db [_ job-plans-response]]
    (-> db
        (assoc :states-tally (get-in job-plans-response [:result :tally] {}))
        (update :plans-report-response merge job-plans-response)
        (dissoc :jobs-report-response))))

(rf/reg-event-db
  :set-jobs-report
  (fn [db [_ jobs-report-response]]
    (-> db
        (assoc :states-tally (get-in jobs-report-response [:result :tally] {}))
        (update :jobs-report-response merge jobs-report-response)
        (dissoc :plans-report-response))))

(rf/reg-event-fx
  :request-plan-rerun
  (fn [{:keys [db]} [_ plan-name]]
    {:db (update db :requested-plan-reruns conj plan-name)
     :request-plan-rerun plan-name}))

(rf/reg-event-db
  :plan-rerun-requested
  (fn [db [_ plan-name]]
    (update db :requested-plan-reruns disj plan-name)))

(defn unpack-executor-error
  [{:keys [status status-text response]}]
  (if (map? response)
    (:executor-error response)  ; executor error proxied by Ephyra.
    (str status " " status-text)))  ; Some other error, e.g. bad conn.

(rf/reg-fx
  :request-plan-rerun
  (fn [plan-name]
    (POST (str "/rerun-plan/" plan-name)
          :handler
          #(rf/dispatch [:log-into-feed
                         {:class "alert-info" :text (str "Requested plan run: " plan-name)}])
          :error-handler
          (fn [err]
            (let [message (unpack-executor-error err)]
              (rf/dispatch [:log-into-feed
                            {:class "alert-danger"
                             :text (str "Failed to request run: " plan-name " - " message)}])))
          :finally (delayed rerun-forced-delay #(rf/dispatch [:plan-rerun-requested plan-name])))))

(rf/reg-event-fx
  :request-job-rerun
  (fn [{:keys [db]} [_ job-name plan-uuid]]
    {:db (update db :requested-job-reruns conj [job-name plan-uuid])
     :request-job-rerun [job-name plan-uuid]}))

(rf/reg-event-db
  :job-rerun-requested
  (fn [db [_ [job-name plan-uuid]]]
    (update db :requested-job-reruns disj [job-name plan-uuid])))

(rf/reg-fx
  :request-job-rerun
  (fn [[job-name plan-uuid]]
    (POST (str "/rerun-job/" job-name "/" plan-uuid)
          :handler
          #(rf/dispatch [:log-into-feed
                         {:class "alert-info"
                          :text (str "Requested job rerun: " job-name ": " plan-uuid)}])
          :error-handler
          (fn [err]
            (let [message (unpack-executor-error err)]
              (rf/dispatch [:log-into-feed
                            {:class "alert-danger"
                             :text (str "Failed to request run: " job-name ": "
                                        plan-uuid " - " message)}])))
          :finally (delayed rerun-forced-delay
                            #(rf/dispatch [:job-rerun-requested [job-name plan-uuid]])))))

(rf/reg-event-fx
  :request-job-history
  (fn [{:keys [db]} _]
    {:db (assoc db :job-invocation-response nil)
     :request-job-history (-> (:query-params db)
                              (select-keys [:job-limit :job-failed-only])
                              (rename-keys {:job-limit :limit, :job-failed-only :failed-only})
                              (assoc :name (get-in db [:route-params :job-name])))}))

(rf/reg-fx
  :request-job-history
  (fn [params]
    (put! request-chan [:job-history params])))

(rf/reg-event-db
  :set-job-history
  (fn [db [_ response]]
    (update-in db [:job-history-response] merge response)))

(rf/reg-event-fx
  :update-job-filtering-limit
  (fn [{:keys [db]} [_ n]]
    (let [new-filtering (assoc (:query-params db) :job-limit n)]
      {:db (assoc db :query-params new-filtering)
       :dispatch-debounce [{:id ::job-history-filtering-updated
                            :timeout 400
                            :action :dispatch
                            :event [:navigate
                                    :job-history
                                    (:route-params db)
                                    new-filtering]}]})))

(rf/reg-event-fx
  :update-job-filtering-failed-only
  (fn [{:keys [db]} _]
    (let [new-filtering (update (:query-params db) :job-failed-only not)]
      {:db (assoc db :query-params new-filtering)
       :dispatch [:navigate :job-history (:route-params db) new-filtering]})))

(rf/reg-event-fx
  :open-job-invocation
  (fn [{:keys [db]} _]
    {:request-job-invocation [(get-in db [:route-params :job-name]) (get-in db [:route-params :uuid])]}))

(rf/reg-fx
  :request-job-invocation
  (fn [[job-name plan-uuid]]
    (put! request-chan [:job-invocation {:name job-name :uuid plan-uuid}])))

(rf/reg-event-db
  :set-job-invocation
  (fn [db [_ response]]
    (assoc db :job-invocation-response response)))

(rf/reg-event-db
  :toggle-version-column-width
  (fn [db _]
    (update db :version-column-expanded not)))

(rf/reg-event-db
  :expand-plans-in-range
  (fn [db [_ root-job-name]]
    (update db :plan-histories-expanded conj root-job-name)))

(rf/reg-event-db
  :collapse-plans-in-range
  (fn [db [_ root-job-name]]
    (update db :plan-histories-expanded disj root-job-name)))
