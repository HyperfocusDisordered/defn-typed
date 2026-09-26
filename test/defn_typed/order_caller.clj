(ns defn-typed.order-caller
  "A literal call of defn-typed.order-total/total: the switch on compiles it to the positional call
   with Order's defaults filled in."
  (:require [defn-typed.order-total :refer [total]]))

(defn call [] (total {:order {:price 3}}))
