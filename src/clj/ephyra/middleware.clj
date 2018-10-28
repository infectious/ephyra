(ns ephyra.middleware
  (:require [ephyra.env :refer [defaults]]
            [clojure.tools.logging :as log]
            [sentry-clj.core :as sentry]
            [ephyra.layout :refer [*app-context* error-page]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ephyra.config :refer [env]]
            [ring.middleware.flash :refer [wrap-flash]]
            [immutant.web.middleware :refer [wrap-session]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]])
  (:import [javax.servlet ServletContext]))

(defn wrap-context [handler]
  (fn [request]
    (binding [*app-context*
              (if-let [context (:servlet-context request)]
                ;; If we're not inside a servlet environment
                ;; (for example when using mock requests), then
                ;; .getContextPath might not exist
                (try (.getContextPath ^ServletContext context)
                     (catch IllegalArgumentException _ context))
                ;; if the context is not specified in the request
                ;; we check if one has been specified in the environment
                ;; instead
                (:app-context env))]
      (handler request))))

(defn wrap-internal-error [handler]
  (fn [req]
    (let
      [tags (select-keys req [:server-name :server-port  :scheme :content-type :request-method
                              :character-encoding])
       extra (select-keys req [:uri :query-string :params :query-params :form-params])]
      (try
        (handler req)
        (catch Throwable t
          (let [event (sentry/send-event {:throwable t :tags tags :extra extra})]
            (log/error t)
            (error-page {:status 500
                         :title "Something very bad has happened!"
                         :message (str "Event sent to Sentry: " event)})))))))

(defn wrap-csrf [handler]
  (wrap-anti-forgery
    handler
    {:error-response
     (error-page
       {:status 403
        :title "Invalid anti-forgery token"})}))

(defn wrap-formats [handler]
  (let [wrapped (wrap-restful-format
                  handler
                  {:formats [:json-kw :transit-json :transit-msgpack]})]
    (fn [request]
      ;; disable wrap-formats for websockets
      ;; since they're not compatible with this middleware
      ((if (:websocket? request) handler wrapped) request))))

(defn wrap-base
  "Applies base wrappers. wrap-internal-error used twice to capture more context (e.g. from
  wrap-defaults), while still reporting errors if it's one of the wrappers below that fail."
  [handler]
  (-> ((:middleware defaults) handler)
      wrap-webjars
      wrap-flash
      (wrap-session {:cookie-attrs {:http-only true}})
      wrap-internal-error
      (wrap-defaults
        (-> site-defaults
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      wrap-context
      wrap-keyword-params
      wrap-internal-error))
