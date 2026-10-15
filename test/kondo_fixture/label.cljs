(ns kondo-fixture.label
  "defn-typed.kondo fixture: a plain defn carrying :malli/schema (two positional args).")

(defn label
  "n units of unit, as text."
  {:malli/schema [:=> [:cat :int :string] :string]}
  [n unit]
  (str n " " unit))
