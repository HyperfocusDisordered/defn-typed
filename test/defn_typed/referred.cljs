(ns defn-typed.referred
  "defn-typed functions that refer-inline-test calls through `:refer`."
  (:require [defn-typed.core :refer [defn-typed defnt]]))

(defn-typed order-total {
  :price    [:int {:min 1}]
  :qty      [:int {:min 1 :default 1}]
  :discount [:int {:min 0 :max 100 :default 0}]
} -> :int
  (quot (* price qty (- 100 discount)) 100)
)

(defn-typed greeting {:who :string :mark [:string {:default "!"}]} -> :string
  (str "hi " who mark)
)

(defnt tip {:amount [:int {:min 0}]} -> :int
  (quot amount 10)
)
