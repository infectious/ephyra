(ns ephyra.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[ephyra started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[ephyra has shut down successfully]=-"))
   :middleware identity})
