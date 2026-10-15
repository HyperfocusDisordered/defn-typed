(ns defn-typed.keep-instrumented-test
  "A defn-typed function malli instrumented stays instrumented when its file is loaded again (the
   reload redefines the var, which drops the wrapper); one that was not instrumented stays plain."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defn-typed.core :as core]
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
           ;; the options a test gave defn-typed.core/instrument! stay with that test
           (reset! @#'core/instrument-options nil)
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

(defn- thrown
  "What a call of the function with a map whose :qty is a string throws, as {:message :data}."
  []
  (let [m {:qty "x"}]
    (try (fn-var m) nil
         (catch clojure.lang.ExceptionInfo e {:message (ex-message e) :data (ex-data e)}))))

(deftest readable-instrumentation-error
  (testing "defn-typed.core/instrument!: the rejection's message names the function, the key, the expected schema and the value; ex-data stays malli's"
    (mi/collect! {:ns ['defn-typed.instrumented-fn]})
    (core/instrument! {:filters [(mi/-filter-ns 'defn-typed.instrumented-fn)]})
    (let [{:keys [message data]} (thrown)]
      (is (= "defn-typed: defn-typed.instrumented-fn/bumped — invalid :qty: expected :int, got \"x\"" message))
      (is (= :malli.core/invalid-input (:type data)))
      (is (= [{:qty "x"}] (:args (:data data))))))
  (testing "malli's own instrument!: malli's message"
    (mi/unstrument! {:filters [(mi/-filter-ns 'defn-typed.instrumented-fn)]})
    (instrument!)
    (is (= ":malli.core/invalid-input" (:message (thrown))))))

(deftest reinstrumentation-keeps-the-options
  (testing "a custom :report given to defn-typed.core/instrument! still reports after the file is loaded again"
    (let [reported (atom [])]
      (mi/collect! {:ns ['defn-typed.instrumented-fn]})
      (core/instrument!
       {:filters [(mi/-filter-ns 'defn-typed.instrumented-fn)]
        :report (fn [type _] (swap! reported conj type))})
      ;; the reporter logs instead of throwing: the call goes on into the body, which throws
      (try (fn-var {:qty "x"}) (catch ClassCastException _))
      (reload!)
      (is (= 2 (wrappers)) "instrumented again")
      (try (fn-var {:qty "x"}) (catch ClassCastException _))
      (is (= [:malli.core/invalid-input :malli.core/invalid-input] @reported)))))
