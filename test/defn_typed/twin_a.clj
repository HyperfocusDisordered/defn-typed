(ns defn-typed.twin-a
  "A function named like one in defn-typed.twin-b: deftests-per-namespace collects both."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defmeta same
  {:inout-tests [[{:n 1} 1]]})

(defn-typed same {:n :int} -> :int
  n
)
