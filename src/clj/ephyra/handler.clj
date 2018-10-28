(ns ephyra.handler
  (:require [compojure.core :refer [routes wrap-routes]]
            [ephyra.layout :refer [error-page]]
            [ephyra.routes.home :refer [home-routes]]
            [ephyra.routes.health :refer [health-routes]]
            [compojure.route :as route]
            [ephyra.env :refer [defaults]]
            [mount.core :as mount]
            [ephyra.middleware :as middleware]))

(mount/defstate init-app
                :start ((or (:init defaults) identity))
                :stop  ((or (:stop defaults) identity)))

(def not-found
  (route/not-found
    (:body
      (error-page {:status 404
                   :title "page not found"}))))

(def app-routes
  (routes
    (-> #'home-routes
        (wrap-routes middleware/wrap-csrf)
        (wrap-routes middleware/wrap-formats))
    not-found))


(def app (middleware/wrap-base #'app-routes))

(def app-healthcheck
  (middleware/wrap-base
    (routes
      (middleware/wrap-formats #'health-routes)
      not-found)))
