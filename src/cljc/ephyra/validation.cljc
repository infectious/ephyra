(ns ephyra.validation
  (:require #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))


(s/def ::limit
  (s/and (s/spec (s/conformer
                   (fn [v]
                     (cond
                       (integer? v) v
                       (string? v)
                       #?(:clj (try (Integer/parseInt v) (catch NumberFormatException e ::s/invalid))
                          :cljs (if (re-matches #"^[0-9]+$" v)
                                  (js/parseInt v 10)
                                  ::s/invalid))
                       :else ::s/invalid))))
         pos?))
