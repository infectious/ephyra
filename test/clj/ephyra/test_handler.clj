(ns ephyra.test-handler
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [cognitect.transit :as transit]
            [ephyra.fixtures :refer [db-setup transient-transaction]]
            [ephyra.handler :refer [app]])
  (:import [java.io ByteArrayInputStream]))


(use-fixtures :once db-setup)
(use-fixtures :each transient-transaction)

(defn read-transit-string [transit-string]
  (let [in (ByteArrayInputStream. (.getBytes transit-string "utf-8"))]
    (transit/read (transit/reader in :json-verbose))))

(deftest test-root-resource
  (let [resp (app (mock/request :get "/"))]
    (is (= (:status resp) 200))
    (is (re-find #"^text/html;" (get-in resp [:headers "Content-Type"])))
    (is (re-find #"Loading\.\." (:body resp)))))

;(deftest test-run-plans-resource
;  (let [resp (app (mock/request :get "/run-plans/"))]
;    (is (= (:status resp) 200))
;    (is (re-find #"application/transit\+json-verbose;"
;                 (get-in resp [:headers "Content-Type"])))
;    (is (= (read-transit-string (:body resp))
;           {:something "TODO"}))))
