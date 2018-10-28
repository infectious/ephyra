(ns ephyra.env
  (:require [selmer.parser :as parser]
            [clojure.tools.logging :as log]
            [ephyra.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (parser/cache-off!)
     (log/info "\n-=[ephyra started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[ephyra has shut down successfully]=-"))
   :middleware wrap-dev})
