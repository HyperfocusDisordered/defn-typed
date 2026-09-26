(ns defn-typed.instrumented-fn
  "The function defn-typed.keep-instrumented-test instruments with malli and reloads from this file."
  (:require [defn-typed.core :refer [defn-typed]]))

(defn-typed bumped {:qty :int} -> :int
  (inc qty)
)
