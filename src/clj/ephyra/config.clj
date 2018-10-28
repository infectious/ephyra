(ns ephyra.config
  (:require [cprop.core :refer [load-config]]
            [cprop.source :as source]
            [mount.core :refer [args defstate]]))

(defstate env :start (load-config
                       :merge
                       [(args)
                        (source/from-system-props)
                        (source/from-env)]))

(defn get-setting
  "Validates the setting exist and returns it (because not everyone likes NPEs)."
  [k]
  (if (contains? env k)
    (env k)
    (throw (RuntimeException. (str "Configuration field " k " not specified.")))))
