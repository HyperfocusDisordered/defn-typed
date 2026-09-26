(ns defn-typed.typed-check-fixture
  "Definitions defn-typed.typed-check-test loads: shout's body contradicts its output schema,
   helped calls a function Typed Clojure has no annotation for (it cannot type it: no finding),
   quiet contradicts its output with its own :typed-check :off, fits is right."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defn helper [x] x)

(defn-typed shout {:n :int} -> :string
  n
)

(defn-typed helped {:n :int} -> :any
  (helper n)
)

(defmeta quiet
  {:typed-check :off})

(defn-typed quiet {:n :int} -> :string
  n
)

(defn-typed fits {:n :int} -> :int
  (inc n)
)
