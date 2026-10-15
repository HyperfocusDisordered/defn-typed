(ns defn-typed.private-fn
  "Private defn-typed functions defn-typed.private-fn-test instruments: one `^:private` on its
   name, one `:private true` in its defmeta; and a public one calling the first."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defn-typed ^:private hidden {:qty :int} -> :int
  (inc qty)
)

(defmeta hidden-by-meta
  {:private true})

(defn-typed hidden-by-meta {:qty :int} -> :int
  (inc qty)
)

(defn-typed shown {:qty :int} -> :int
  (hidden {:qty qty})
)
