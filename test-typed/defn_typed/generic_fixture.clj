(ns defn-typed.generic-fixture
  "Generic defn-typed functions (type variables in the signature) that defn-typed.generic-test
   type-checks, beside first-of-any, the same function with :any where first-of has T."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defmeta first-of
  {:doc "Первый элемент списка — того же типа, что его элементы."
   :inout-tests [[{:xs [3 4]} 3]
                 [{:xs []}    nil]]})

(defn-typed first-of [T] {:xs [:sequential T]} -> [:maybe T]
  (first xs)
)

(defn-typed first-of-any {:xs [:sequential :any]} -> :any
  (first xs)
)

(defn-typed value-of [T] {
  :m [:map-of :keyword T]
  :k :keyword
} -> [:maybe T]
  (get m k)
)

(defn-typed tagged [T] {:item T :n :int} -> [:tuple T :int]
  [item n]
)

(defn box
  "A schema of a box holding a value of schema t."
  [t]
  [:map [:value t]])

(defn-typed unboxed [T] {:b (box T)} -> T
  (:value b)
)
