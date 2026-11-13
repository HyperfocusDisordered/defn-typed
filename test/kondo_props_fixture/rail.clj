(ns kondo-props-fixture.rail
  "defn-typed.kondo fixture: rows typed by `chip-props`, referred from another ns, as a list, a
   bare row and an optional one."
  (:require [defn-typed.core :refer [defn-typed]]
            [kondo-props-fixture.chip :refer [chip-props]]))

(defn-typed rail {
  :chips [:sequential chip-props]
  :lead  chip-props
  :tail  [{:optional true} [:maybe chip-props]]
} -> :int
  (+ (count chips) (if lead 1 0) (if tail 1 0))
)
