(ns kondo-props-fixture.chip
  "defn-typed.kondo fixture: rows typed by the `<name>-props` var another defn-typed function defines,
   in its own ns (`chips`) and from another ns (rail.clj)."
  (:require [defn-typed.core :refer [defn-typed]]))

(defn-typed chip {
  :label  :string
  :active [:boolean {:default false}]
  :count  [{:optional true} [:maybe :int]]
} -> :string
  (str label count)
)

(defn-typed chips {
  :chips [:sequential chip-props]
} -> :int
  (count chips)
)
