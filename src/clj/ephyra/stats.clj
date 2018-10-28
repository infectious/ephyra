(ns ephyra.stats
      (:require [mount.core :as mount :refer [defstate]]
                [clj-statsd :as statsd]
                [ephyra.config :refer [env]]))

(defstate stats
  :start (statsd/setup (env :stats-host "127.0.0.1") (env :stats-port 8125)))
