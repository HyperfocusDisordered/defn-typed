(ns inout.core-test
  "Runs the in/out cases written next to inout.core's own functions. Below them: the pair format
   and what `defn-typed` and `defmeta` expand to, in cljs."
  (:require [cljs.test :refer [deftest is testing]]
            [inout.core :as inout :refer [defn-typed defmeta]]
            [malli.instrument :as mi]))

(defn- summary [results]
  {:cases (reduce + 0 (map :cases results))
   :failures (vec (mapcat :failures results))})

(deftest inout-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'inout.core))]
    (testing "inout.core"
      (is (pos? cases))
      (is (= [] failures)))))

(defmeta padded
  {:doc "a + b, b defaulting to 2."
   :inout-tests [[{:a 1}      3]
                 [{:a 1 :b 5} 6]]})

(defn-typed padded
  ^{:closed true}
  {:a :int
   :b [{:optional true :default 2} :int]} -> :int

  (+ a b))

(defn- incremented
  {:inout-tests [[[1] 2]
                 [[5] 6]]}
  [n]
  (inc n))

(deftest pairs-are-the-cases
  (testing "registered pairs and attr-map pairs read as [i args expected]"
    (is (= [[0 [{:a 1}] 3] [1 [{:a 1 :b 5}] 6]] (vec (inout/var-cases #'padded))))
    (is (= [[0 [1] 2] [1 [5] 6]] (vec (inout/var-cases #'incremented)))))
  (testing "check-var runs them"
    (is (= {:var `padded :cases 2 :failures []} (inout/check-var #'padded)))
    (is (= {:var `incremented :cases 2 :failures []} (inout/check-var #'incremented)))))

(deftest a-case-that-is-not-a-pair-throws
  (doseq [bad [[[[1] 2 3]] [[[1]]] [[1 2]] [nil] '([[1] 2])]]
    (let [message (try (inout/register-tests! #'incremented bad) nil
                       (catch :default e (ex-message e)))]
      (is (re-find #"incremented" (str message)) (pr-str bad))))
  (swap! inout/registry dissoc `incremented))

(deftest defn-typed-expansion
  (testing "the input map (its metadata = the table's own props) turns into [:map …], def'd as <name>-props and referenced from :malli/schema"
    (is (= [:map {:closed true} [:a :int] [:b {:optional true :default 2} :int]] padded-props))
    ;; cljs var metadata keeps the source form; malli's collect! evaluates it (see below)
    (is (= '[:=> [:cat padded-props] :int] (:malli/schema (meta #'padded)))))
  (testing "defaults come from the table"
    (is (= 3 (padded {:a 1})))
    (is (= 6 (padded {:a 1 :b 5})))))

(deftest defmeta-expansion
  (testing "defmeta above the defn-typed: :doc goes into the defn at compile time; the registry also keeps it beside the cases (where a plain defn below keeps it)"
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (= {:doc "a + b, b defaulting to 2."} (:meta (get @inout/registry `padded))))
    (is (= {:var `padded :cases 2 :failures []} (inout/check-var #'padded)))))

(mi/collect! {:ns [inout.core-test]})

(deftest defn-typed-schema-is-instrumented
  (mi/instrument! {:filters [(mi/-filter-ns 'inout.core-test)]})
  (try
    (testing "a good call passes (a defaulted key may be absent: its row is :optional), an unknown key is rejected as :malli.core/invalid-input"
      (is (= 3 (padded {:a 1})))
      (let [data (try (padded {:a 1 :c 3}) nil
                      (catch :default e (ex-data e)))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [{:a 1 :c 3}] (vec (:args (:data data)))))))
    (finally
      (mi/unstrument! {:filters [(mi/-filter-ns 'inout.core-test)]}))))
