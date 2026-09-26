(ns defn-typed.keep-instrumented-test
  "A defn-typed function malli instrumented stays instrumented when its file is loaded again (the
   reload redefines the var, which drops the wrapper); one that was not instrumented stays plain."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defn-typed.instrumented-fn]
            [malli.instrument :as mi]))

(def fn-var #'defn-typed.instrumented-fn/bumped)

(defn- instrument! []
  (mi/collect! {:ns ['defn-typed.instrumented-fn]})
  (mi/instrument! {:filters [(mi/-filter-ns 'defn-typed.instrumented-fn)]}))

(defn- reload! []
  (require 'defn-typed.instrumented-fn :reload))

(defn- failure
  "What a call of the function with a map whose :qty is a string throws: the ex-data's :type of an
   ex-info, else the exception's class."
  []
  (let [m {:qty "x"}]
    (try (fn-var m) :no-throw
         (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))
         (catch Exception e (class e)))))

(defn- wrappers
  "How many instrumentation wrappers the var's value sits in."
  []
  (count (take-while some? (iterate #(some-> % meta ::mi/original) @fn-var))))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (mi/unstrument! {:filters [(mi/-filter-ns 'defn-typed.instrumented-fn)]})
           (reload!)))))

(deftest instrumented-stays-instrumented
  (instrument!)
  (testing "instrumented: a bad real-map call is rejected as :malli.core/invalid-input"
    (is (= :malli.core/invalid-input (failure))))
  (reload!)
  (testing "after the file is loaded again, the same call is still rejected"
    (is (= :malli.core/invalid-input (failure)))
    (is (= 2 (wrappers)) "one wrapper around the new body fn"))
  (testing "instrumenting it again leaves one wrapper"
    (instrument!)
    (is (= 2 (wrappers)))
    (is (= :malli.core/invalid-input (failure)))))

(deftest uninstrumented-stays-plain
  (reload!)
  (testing "never instrumented: the reload leaves it plain, the bad call reaches the body"
    (is (= ClassCastException (failure)))
    (is (= 1 (wrappers)))))
