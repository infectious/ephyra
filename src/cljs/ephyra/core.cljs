(ns ephyra.core
  (:require [re-frame.core :as rf]
            [ephyra.ajax :refer [load-interceptors!]]
            [ephyra.async :refer [run-refresh-query-loop!]]
            [ephyra.components :refer [mount-components]]
            [ephyra.handlers]
            [ephyra.routing :as routing]
            [ephyra.subs])
  (:require-macros [reagent.ratom :refer [reaction]]))


(defn ^:export init! []
  (load-interceptors!)
  (rf/dispatch-sync [:initialize])
  (routing/start!)
  (mount-components)
  (run-refresh-query-loop!))
