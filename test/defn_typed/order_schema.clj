(ns defn-typed.order-schema
  "The schema defn-typed.order-total types a row by (`:order Order`); defn-typed.schema-deps-test
   redefines Order here.")

(def Order [:map [:price :int] [:qty [:int {:default 1}]]])
