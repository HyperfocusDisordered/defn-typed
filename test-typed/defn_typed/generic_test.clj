(ns defn-typed.generic-test
  "Type variables in a defn-typed signature, with Typed Clojure (clojure -M:typed): install! annotates
   the function and its <name>--positional `(t/All [T] …)`, so a call's result keeps the type of
   what went in (first-of); the same function with :any in T's place gives t/Any. [:map-of :keyword
   T], [:tuple T :int] and a schema a function builds, (box T), carry T through. A function without
   type variables keeps its annotations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.core]
            [defn-typed.generic-fixture]
            [defn-typed.typed-clojure :refer [install! check-form!]]))

(def installed
  (delay (install! {:namespaces ['defn-typed.generic-fixture]})))

(defn- anns
  "The t/ann forms install! evaluates for the fixture's function f."
  [f]
  (let [bridge #(deref (resolve (symbol "defn-typed.typed-clojure" %)))]
    ((bridge "fn-anns") ((bridge "defn-typed-fn") (resolve (symbol "defn-typed.generic-fixture" (name f)))))))

(defn- errors
  "[first line of the message, the Arguments: line] of each type error of form, checked in the fixture's
   namespace."
  [form]
  @installed
  (mapv (fn [{:keys [message]}]
          [(first (str/split-lines message)) (some-> (re-find #"Arguments:\n\t([^\n]+)" message) second)])
        (check-form! {:ns 'defn-typed.generic-fixture :form form :file "test-typed/defn_typed/generic_fixture.clj"})))

(def inc-rejects "Function inc could not be applied to arguments:")

(deftest first-of-is-generic
  (testing "the function, its --positional and -props: T bound by t/All in both function types"
    (is (= '[(typed.clojure/ann defn-typed.generic-fixture/first-of-props typed.clojure/Any)
             (typed.clojure/ann defn-typed.generic-fixture/first-of--positional
                                (typed.clojure/All [T] [(typed.clojure/SequentialColl T) :-> (typed.clojure/Nilable T)]))
             (typed.clojure/ann defn-typed.generic-fixture/first-of
                                (typed.clojure/All [T] [(quote {:xs (typed.clojure/SequentialColl T)}) :-> (typed.clojure/Nilable T)]))]
           (anns 'first-of))))
  (testing "a call's result has the items' type: a number, once nil is ruled out, goes into inc"
    (is (= [] (errors '(inc (or (first-of {:xs [1 2]}) 0))))))
  (testing "with :any in T's place the result is t/Any, which inc rejects"
    (is (= [[inc-rejects "typed.clojure/Any"]] (errors '(inc (or (first-of-any {:xs [1 2]}) 0))))))
  (testing "strings in, a string out: inc rejects it; nil (an empty list) is part of the result"
    (is (= [[inc-rejects "(typed.clojure/U (typed.clojure/Val 0) (typed.clojure/Val \"a\"))"]]
           (errors '(inc (or (first-of {:xs ["a"]}) 0)))))
    (is (= [[inc-rejects "(typed.clojure/Nilable (typed.clojure/U (typed.clojure/Val 1) (typed.clojure/Val 2)))"]]
           (errors '(inc (first-of {:xs [1 2]})))))))

(deftest map-of-values
  (testing "[:map-of :keyword T]: T is the values' type"
    (is (= '(typed.clojure/ann defn-typed.generic-fixture/value-of
                               (typed.clojure/All [T] [(quote {:m (typed.clojure/Map typed.clojure/Kw T), :k typed.clojure/Kw})
                                                       :-> (typed.clojure/Nilable T)]))
           (last (anns 'value-of))))
    (is (= [] (errors '(let [n (value-of {:m {:a 1} :k :a})] (if n (inc n) 0)))))
    (is (= [[inc-rejects "(typed.clojure/Val \"x\")"]] (errors '(let [n (value-of {:m {:a "x"} :k :a})] (if n (inc n) 0)))))))

(deftest tuple-is-a-vector-of-types
  (testing "[:tuple T :int] → (t/HVec [T t/AnyInteger]): each item keeps its own type"
    (is (= '(typed.clojure/ann defn-typed.generic-fixture/tagged--positional
                               (typed.clojure/All [T] [T typed.clojure/AnyInteger :-> (typed.clojure/HVec [T typed.clojure/AnyInteger])]))
           (second (anns 'tagged))))
    (is (= [] (errors '(let [[s n] (tagged {:item "a" :n 1})] (str s (inc n))))))
    (is (= [[inc-rejects "(typed.clojure/Val \"a\")"]] (errors '(let [[s _] (tagged {:item "a" :n 1})] (inc s))))))
  (testing "a tuple without type variables"
    (is (= '(typed.clojure/HVec [typed.clojure/AnyInteger typed.clojure/Str])
           (@#'defn-typed.typed-clojure/type-of [:tuple :int :string])))))

(deftest a-schema-a-function-builds
  (testing "(box T), box a function returning [:map [:value t]]: T inside the map it builds"
    (is (= '(typed.clojure/ann defn-typed.generic-fixture/unboxed
                               (typed.clojure/All [T] [(quote {:b (quote {:value T})}) :-> T]))
           (last (anns 'unboxed))))
    (is (= [] (errors '(inc (unboxed {:b {:value 1}})))))
    (is (= [[inc-rejects "(typed.clojure/Val \"x\")"]] (errors '(inc (unboxed {:b {:value "x"}})))))))

(deftest without-type-variables-unchanged
  (testing "no t/All, and no t/ann of the function itself: typed.malli's provider types it from :malli/schema"
    (is (= '[(typed.clojure/ann defn-typed.generic-fixture/first-of-any-props typed.clojure/Any)
             (typed.clojure/ann defn-typed.generic-fixture/first-of-any--positional
                                [(typed.clojure/SequentialColl typed.clojure/Any) :-> typed.clojure/Any])]
           (anns 'first-of-any)))))

(deftest the-fixture-loads-clean
  (testing "every body type-checks as it loads, and the cases pass"
    (let [err (java.io.StringWriter.)]
      (binding [*err* err] (require 'defn-typed.generic-fixture :reload))
      (is (= "" (str err))))
    (is (= {:var 'defn-typed.generic-fixture/first-of :cases 2 :failures []}
           (defn-typed.core/check-var (resolve 'defn-typed.generic-fixture/first-of))))))
