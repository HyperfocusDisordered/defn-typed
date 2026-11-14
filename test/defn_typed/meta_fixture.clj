(ns defn-typed.meta-fixture
  "Definitions whose var metadata meta_test.clj pins."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(def id-path [:map [:id :int]])

(defn-typed plain {:request :map} -> :map
  request
)

(defn-typed by-var {:request :map :path-params id-path} -> :map
  {:request request :id (:id path-params)}
)

(defmeta documented
  {:doc "Adds one."
   :inout-tests [[{:n 1} 2]
                 [{} 1]]})

(defn-typed documented {:n [:int {:min 0 :default 0}]} -> :int
  (inc n)
)

(defn-typed ^:private hidden {:n :int} -> [:maybe :int]
  n
)

(defmeta guarded {:doc "Checked in production." :malli-in-prod true})

(defn-typed guarded {:n :int} -> :int
  n
)

(defn caller []
  [(plain {:request {:a 1}}) (documented {:n 4})])

(defn hidden-answer []
  (hidden {:n 1}))
