(ns defn-typed.stale-caller
  "A literal call of defn-typed.stale-callee/pair, which the switch on compiles to its positional
   call; `loads` counts the loads of this file."
  (:require [defn-typed.stale-callee :refer [pair]]))

(defonce loads (atom 0))
(swap! loads inc)

(defn call [] (pair {:a 1 :b 2}))
