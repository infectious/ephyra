(ns ephyra.ajax
  (:require [ajax.core :as ajax]))

(defn local-uri? [{:keys [uri]}]
  (not (re-find #"^\w+?://" uri)))

(defn default-headers [request]
  (if (local-uri? request)
    (-> request
        (update :uri #(str js/context %))
        (update :headers #(merge {"x-csrf-token" js/csrfToken} %)))
    request))

(defn urlquote-kv
  "Quotes keys and values to make them safe for query params."
  [m]
  (reduce-kv
    (fn [m-quoted k v]
      (assoc m-quoted
             (js/encodeURI (name k))
             (some-> v (js/encodeURI))))
    {}
    m))

(defn quoted-get-params [request]
  (if (= (:method request) "GET")
    (update request :params urlquote-kv)
    request))

(defn load-interceptors! []
  (swap! ajax/default-interceptors
         conj
         (ajax/to-interceptor {:name "default headers"
                               :request default-headers})
         (ajax/to-interceptor {:name "quote GET params"
                               :request quoted-get-params})))
