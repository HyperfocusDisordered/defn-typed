(ns defn-typed.core-test
  "Runs the in/out cases written next to defn-typed.core's own functions. Below them: the pair format
   and what `defn-typed` and `defmeta` expand to, in cljs."
  (:require [cljs.test :refer [deftest is testing]]
            [defn-typed.core :as core :refer [defn-typed defnt defmeta]]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(defn- summary [results]
  {:cases (reduce + 0 (map :cases results))
   :failures (vec (mapcat :failures results))})

(deftest inout-cases
  (let [{:keys [cases failures]} (summary (core/check-ns 'defn-typed.core))]
    (testing "defn-typed.core"
      (is (pos? cases))
      (is (= [] failures)))))

(defmeta padded
  {:doc "a + b, b defaulting to 2."
   :inout-tests [[{:a 1}      3]
                 [{:a 1 :b 5} 6]]})

(defn-typed padded
  {:a :int
   :b [:int {:default 2}]} -> :int

  (+ a b))

(defn- incremented
  {:inout-tests [[[1] 2]
                 [[5] 6]]}
  [n]
  (inc n))

(deftest pairs-are-the-cases
  (testing "registered pairs and attr-map pairs read as [i args expected]"
    (is (= [[0 [{:a 1}] 3] [1 [{:a 1 :b 5}] 6]] (vec (core/var-cases #'padded))))
    (is (= [[0 [1] 2] [1 [5] 6]] (vec (core/var-cases #'incremented)))))
  (testing "check-var runs them"
    (is (= {:var `padded :cases 2 :failures []} (core/check-var #'padded)))
    (is (= {:var `incremented :cases 2 :failures []} (core/check-var #'incremented)))))

(deftest a-case-that-is-not-a-pair-throws
  (doseq [bad [[[[1] 2 3]] [[[1]]] [[1 2]] [nil] '([[1] 2])]]
    (let [message (try (core/register-tests! #'incremented bad) nil
                       (catch :default e (ex-message e)))]
      (is (re-find #"incremented" (str message)) (pr-str bad))))
  (swap! core/registry dissoc `incremented))

(deftest defn-typed-expansion
  (testing "the input map turns into [:map …], def'd as <name>-props and referenced from :malli/schema"
    (is (= [:map [:a :int] [:b {:optional true} [:int {:default 2}]]] padded-props))
    ;; cljs var metadata keeps the source form; malli's collect! evaluates it (see below)
    (is (= '[:=> [:cat padded-props] :int] (:malli/schema (meta #'padded))))
    (testing "the arglist is the rows as a destructuring map, so doc shows the inputs"
      (is (= '([{:keys [a b]}]) (:arglists (meta #'padded))))))
  (testing "defaults come from the row type's own props; the absent key is filled"
    (is (= 3 (padded {:a 1})))
    (is (= 6 (padded {:a 1 :b 5})))))

(deftest defmeta-expansion
  (testing "defmeta above the defn-typed: :doc goes into the defn at compile time; the registry also keeps it beside the cases (where a plain defn below keeps it)"
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (= {:doc "a + b, b defaulting to 2."} (:meta (get @core/registry `padded))))
    (is (= {:var `padded :cases 2 :failures []} (core/check-var #'padded)))))

(defmeta shorter
  {:doc "a + b under the short name, b defaulting to 2."
   :inout-tests [[{:a 1}      3]
                 [{:a 1 :b 5} 6]]})

(defnt shorter {
  :a :int
  :b [:int {:default 2}]
} -> :int
  (+ a b)
)

(deftest defnt-is-defn-typed
  (testing "a function defined with the :refer'red defnt is a defn-typed function: <name>-props, :malli/schema, the defmeta's :doc and cases, defaults"
    (is (= [:map [:a :int] [:b {:optional true} [:int {:default 2}]]] shorter-props))
    (is (= '[:=> [:cat shorter-props] :int] (:malli/schema (meta #'shorter))))
    (is (= "a + b under the short name, b defaulting to 2." (:doc (meta #'shorter))))
    (is (= {:var `shorter :cases 2 :failures []} (core/check-var #'shorter)))
    (is (= 3 (shorter {:a 1})))
    (is (= 6 (shorter {:a 1 :b 5})))))

(defn-typed cart-total {
  :items [:sequential [:map [:price [:int {:min 1}]] [:qty [:int {:min 1 :default 1}]]]]
} -> :int
  (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))
)

(mi/collect! {:ns [defn-typed.core-test]})

(deftest defn-typed-schema-is-instrumented
  (mi/instrument! {:filters [(mi/-filter-ns 'defn-typed.core-test)]})
  (try
    (testing "a good call passes (a defaulted key may be absent: the macro made its row :optional), a real map's key beyond the rows passes (the map is open), a wrong type is rejected as :malli.core/invalid-input"
      (is (= 3 (padded {:a 1})))
      (is (= 6 (padded {:a 1 :b 5})))
      (let [m {:a 1 :c 3}]
        (is (= 3 (padded m))))
      (let [m {:a "x"}
            data (try (padded m) nil
                      (catch :default e (ex-data e)))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [{:a "x"}] (vec (:args (:data data)))))))
    (testing "an item of a :sequential row may leave its defaulted key out; a wrong item type is rejected at its path"
      (let [m {:items [{:price 100}]}]
        (is (= 100 (cart-total m))))
      (let [m {:items [{:price "x"}]}
            data (try (cart-total m) nil
                      (catch :default e (ex-data e)))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [":items 0 :price · should be an integer · got \"x\""]
               (core/malli-reasons {:explain m/explain :error-message me/error-message} data)))))
    (finally
      (mi/unstrument! {:filters [(mi/-filter-ns 'defn-typed.core-test)]}))))
