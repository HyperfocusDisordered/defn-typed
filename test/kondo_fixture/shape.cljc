(ns kondo-fixture.shape
  "defn-typed.kondo fixture: rows typed by same-file defs (one carrying a default) and a [:fn …]."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(def Point
  [:map [:x :int] [:y :int]])

(def Step
  [:int {:default 1}])

(defmeta moved
  {:doc "point moved right by step."
   :inout-tests [[{:point {:x 1 :y 2} :step 2 :scale 1} {:x 3 :y 2}]]})

(defn-typed moved {
  :point Point
  :step  Step
  :scale [:fn pos?]
} -> Point
  (update point :x + (* scale step))
)
