(ns defn-typed.schema-typo
  "order-total's :qty row names a schema malli does not know (`:it`): loading this file fails."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defmeta order-total
  {:doc "Order total: price times quantity, minus a percentage discount."
   :inout-tests [[{:price 100} 100]
                 [{:price 100 :qty 2} 200]
                 [{:price 100 :qty 2 :discount 25} 150]]})

(defn-typed order-total {
  :price    [:int {:min 1}]
  :qty      [:it {:min 1 :default 1}]
  :discount [:int {:min 0 :max 100 :default 0}]
} -> :int
  (quot (* price qty (- 100 discount)) 100)
)
