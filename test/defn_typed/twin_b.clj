(ns defn-typed.twin-b
  "A function named like one in defn-typed.twin-a: deftests-per-namespace collects both."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defmeta same
  {:inout-tests [[{:n 1} 2]
                 [{:n 2} 3]]})

(defn-typed same {:n :int} -> :int
  (inc n)
)
