(ns kondo-fixture.order
  "defn-typed.kondo fixture: a defn-typed function with a defaulted row and an optional one."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defmeta order-total
  {:doc "The order's total, price × qty, as text followed by the note."
   :inout-tests [[{:price 100}                    "100"]
                 [{:price 100 :qty 3 :note " ok"} "300 ok"]]})

(defn-typed order-total {
  :price :int
  :qty   [:int {:min 1 :default 1}]
  :note  [{:optional true} [:maybe :string]]
} -> :string
  (str (* price qty) note)
)
