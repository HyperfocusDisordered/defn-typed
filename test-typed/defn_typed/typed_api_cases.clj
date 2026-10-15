(ns defn-typed.typed-api-cases
  "In/out cases for the pure public functions of defn-typed.typed-clojure, written from outside the
   way a consumer writes them (clojure -M:typed): the function here takes the arguments of the
   library function it covers as one map and returns what it returns."
  (:require [defn-typed.core :as core :refer [defn-typed defmeta]]
            [defn-typed.typed-clojure :as typed]))

(defmeta could-not-type?
  {:doc "defn-typed.typed-clojure/could-not-type?: whether a checker message says Typed Clojure could not type the code, not that the code has a type error; a label `input of f: ` / `output of f: ` does not matter."
   :inout-tests [[{:message "Unannotated var clojure.string/join"}                         true]
                 [{:message "input of f: Unannotated var user/helper"}                     true]
                 [{:message "Loop requires more annotations"}                              true]
                 [{:message "output of g: Loop requires more annotations"}                 true]
                 [{:message "Missing type for binding: clojure.core/*out*"}                true]
                 [{:message "output of g: Missing type for binding: user/*dyn*"}           true]
                 [{:message "Type mismatch:\n Expected: Int\n Actual: Str"}                false]
                 [{:message "output of shout: Type mismatch: expected Str, got Int"}       false]
                 [{:message "The call f has an Unannotated var inside"}                    false]
                 [{:message ""}                                                            false]]})

(defn-typed could-not-type? {:message :string} -> :boolean
  (typed/could-not-type? message)
)
