(ns ephyra.spec
  "clojure.spec utils"
  (:require [clojure.spec :as s]
            [slingshot.slingshot :refer [throw+]]))


(defn must-conform
  "Conforms v to the spec or throws ex-info on failure.

  The return value of s/conform if then passed to s/unform to undo 'destructuring' done by s/or.
  Conformers with identity unformers will not be undone.

  Unforming is done because for specs like `(s/or :true true? :false false?)` we want the true
  or false values, to remain as they are, not [:true true] or [:false false]."
  [spec v]
  (let [conformed (s/conform spec v)]
    (if (= conformed ::s/invalid)
      (throw+ {:type ::s/invalid :explanation (s/explain-str spec v)})
      (s/unform spec conformed))))
