(ns defn-typed.forward-call-cljs-test
  "cljs: a literal call compiled above its callee's defn-typed definition in the same file is a
   plain call (the call-site expander does not exist yet); the definition reports it under
   `:literal-check`, as in clj."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [defn-typed.core])
  (:require-macros [defn-typed.compile-err :refer [expansion-err]]))

(declare later)

(defn use-later [] (later {:x 1}))

(def printed
  (expansion-err
   '(defn-typed.core/defn-typed later {:x :int} -> :int
     x
   )))

(deftest forward-call-is-reported
  (is (= ["calls defn-typed.forward-call-cljs-test/later before its definition — the call compiles as a map, not positionally; move the definition above the call"]
         (map #(second (re-find #"^defn-typed: test/defn_typed/forward_call_cljs_test\.cljs:12 (.*)$" %))
              (str/split-lines printed)))))
