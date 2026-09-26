(ns defn-typed.order-same-ns
  "A schema and the defn-typed function typed by it in one namespace; `loads` counts the loads of
   this file."
  (:require [defn-typed.core :refer [defn-typed]]))

(defonce loads (atom 0))
(swap! loads inc)

(def Line [:map [:qty [:int {:default 1}]]])

(defn-typed qty-of {:line Line} -> :int
  (:qty line)
)
