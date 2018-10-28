(ns ephyra.subs
  (:require [re-frame.core :as rf]
            [cljs-time.coerce :refer [to-date]]
            [ephyra.constants :refer [max-notifications]]))

(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :query-params :query-params)
(rf/reg-sub :date-from :<- [:query-params] (fn [{date :date-from} _] (to-date date)))
(rf/reg-sub :date-to :<- [:query-params] (fn [{date :date-to} _] (to-date date)))
(rf/reg-sub :filtering-name :<- [:query-params] (fn [filtering _] (:name filtering)))
(rf/reg-sub :filtering-limit :<- [:query-params] (fn [filtering _] (:limit filtering)))
(rf/reg-sub :fetching? :fetching?)
(rf/reg-sub
  :updates-feed
  (fn [db _]
    (when-let [updates (not-empty (:updates-feed db))]
      (take-last max-notifications updates))))

(rf/reg-sub :plans-report-response (fn [db _] (:plans-report-response db)))
(rf/reg-sub :jobs-report-response (fn [db _] (:jobs-report-response db)))

(defn extract-list [server-response]
  (update server-response :result :items))

(rf/reg-sub :job-plans-items :<- [:plans-report-response] extract-list)
(rf/reg-sub :jobs-report-items :<- [:jobs-report-response] extract-list)

(rf/reg-sub :plan-rerun-request-pending (fn [db _] (:requested-plan-reruns db)))
(rf/reg-sub :job-rerun-request-pending (fn [db _] (:requested-job-reruns db)))

(rf/reg-sub :states-tally (fn [db _] (:states-tally db)))

(rf/reg-sub :selected-job-filtering-limit :<- [:query-params]
            (fn [filtering _] (:job-limit filtering)))
(rf/reg-sub :selected-job-filtering-failed-only :<- [:query-params]
            (fn [filtering _] (:job-failed-only filtering)))
(rf/reg-sub :job-history-response (fn [db _] (:job-history-response db)))

(rf/reg-sub :route-params (fn [db _] (:route-params db)))
(rf/reg-sub :focus :<- [:route-params] (fn [route-params _] (:focus route-params)))
(rf/reg-sub :tab :<- [:route-params] (fn [route-params _] (:tab route-params)))
(rf/reg-sub :selected-job-name :<- [:route-params] (fn [route-params _] (:job-name route-params)))
(rf/reg-sub :selected-job-invocation-uuid :<- [:route-params]
            (fn [route-params _] (:uuid route-params)))

(rf/reg-sub :job-invocation-response (fn [db _] (:job-invocation-response db)))

(rf/reg-sub :version-column-expanded (fn [db _] (:version-column-expanded db)))
(rf/reg-sub :plan-histories-expanded (fn [db _] (:plan-histories-expanded db)))
