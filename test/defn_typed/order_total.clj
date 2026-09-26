(ns defn-typed.order-total
  "A defn-typed function whose row is typed by the symbol Order of defn-typed.order-schema; `loads`
   counts the loads of this file."
  (:require [defn-typed.core :refer [defn-typed]]
            [defn-typed.order-schema :refer [Order]]))

(defonce loads (atom 0))
(swap! loads inc)

(defn-typed total {:order Order} -> :int
  (* (:price order) (:qty order))
)
