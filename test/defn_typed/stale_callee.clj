(ns defn-typed.stale-callee
  "The function defn-typed.stale-callers-test redefines with other rows; defn-typed.stale-caller
   calls it with a map literal."
  (:require [defn-typed.core :refer [defn-typed]]))

(defn-typed pair {
  :a :int
  :b :int
} -> :any
  [a b]
)
