(ns bikes.auction.inout-test
  "Runs the in/out cases written next to their functions: one `<fn>-inout` test per function
   with cases, for every namespace of the source tree the dev loader loads (contracts/
   source-namespaces), so a namespace that gains cases is in this suite without being listed.
   Below them: the pair format itself and what `defnmalli` and `defmeta` expand to."
  (:require [bikes.auction.contracts :as contracts]
            [bikes.auction.inout :as inout :refer [defnmalli defmeta]]
            [clojure.test :refer [deftest is testing]]
            [malli.instrument :as mi]))

(run! inout/deftests! (contracts/source-namespaces))

(defmeta padded
  {:doc "a + b, b defaulting to 2."
   :inout-tests [[{:a 1}      3]
                 [{:a 1 :b 5} 6]]})

(defnmalli padded
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
    (let [e (try (inout/register-tests! #'incremented bad) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (pr-str bad))
      (is (re-find #"incremented" (str (ex-message e))) (pr-str bad))))
  (inout/register-tests! #'incremented [[[1] 2]])
  (swap! inout/registry dissoc `incremented))

(deftest defnmalli-expansion
  (testing "the input map (its metadata = the table's own props) turns into [:map …], def'd as <name>-props and referenced from :malli/schema"
    (is (= [:map {:closed true} [:a :int] [:b {:optional true :default 2} :int]] padded-props))
    (is (= [:=> [:cat padded-props] :int] (:malli/schema (meta #'padded))))
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (= [1] (map count (:arglists (meta #'padded))))))
  (testing "a map without metadata turns into a bare [:map …] in entry order; `key [props schema]` is a row with props; every row key is a local, a qualified key by its name"
    (let [expansion (macroexpand-1 '(bikes.auction.inout/defnmalli f {:a :int :x/b [{:optional true} :int]} -> :any [a b]))]
      (is (= '(def f-props [:map [:a :int] [:x/b {:optional true} :int]]) (second expansion)))
      (is (= '{:keys [a x/b]} (first (second (last (last expansion))))))))
  (testing "a value vector not led by a map is the row's schema"
    (is (= '(def f-props [:map [:a [:maybe :int]]])
           (second (macroexpand-1 '(bikes.auction.inout/defnmalli f {:a [:maybe :int]} -> :any a))))))
  (testing "a vector returned as the value is the body, not an argument vector"
    (is (some? (macroexpand-1 '(bikes.auction.inout/defnmalli f {:a :int} -> :any [:div a]))))
    (is (some? (macroexpand-1 '(bikes.auction.inout/defnmalli f {:a :int} -> :any [comp {:x a}])))))
  (testing "defaults come from the table"
    (is (= 3 (padded {:a 1})))
    (is (= 6 (padded {:a 1 :b 5}))))
  (testing "a docstring, an input that is not one map (a vector, [:map …], a symbol, two forms), no rows, a non-keyword key, a bad [props schema], an argument vector, or a missing ->, fails at compile time naming the fn"
    (doseq [[form message] [['(bikes.auction.inout/defnmalli f "doc" {:a :int} -> :any a) #"docstring goes to defmeta"]
                            ['(bikes.auction.inout/defnmalli f [[:a :int]] -> :any a) #"the input is a map: \{key schema …\}"]
                            ['(bikes.auction.inout/defnmalli f [:map [:a :int]] -> :any a) #"the input is a map"]
                            ['(bikes.auction.inout/defnmalli f some-props -> :any a) #"the input is a map"]
                            ['(bikes.auction.inout/defnmalli f {:a :int} {:b :int} -> :any a) #"the input is a map"]
                            ['(bikes.auction.inout/defnmalli f -> :any a) #"no input rows"]
                            ['(bikes.auction.inout/defnmalli f {} -> :any a) #"no input rows in the map"]
                            ['(bikes.auction.inout/defnmalli f ^{:closed true} {} -> :any a) #"no input rows in the map"]
                            ['(bikes.auction.inout/defnmalli f {"a" :int} -> :any a) #"a key of the input map is a keyword, got \"a\""]
                            ['(bikes.auction.inout/defnmalli f {:a [{:optional true}]} -> :any a) #"a row with props is :a \[props schema\]"]
                            ['(bikes.auction.inout/defnmalli f {:a :int} -> :any [{:keys [a]}] a) #"args are bound from the rows"]
                            ['(bikes.auction.inout/defnmalli f {:a :int} -> :any [m] m) #"args are bound from the rows"]
                            ['(bikes.auction.inout/defnmalli f {:a :int} => :any a) #"expected ->"]]]
      (is (re-find (re-pattern (str "^defnmalli f: .*" message))
                   (try (pr-str (macroexpand-1 form))
                        (catch Exception e (ex-message (or (ex-cause e) e)))))
          (pr-str form)))))

(deftest defmeta-expansion
  (testing "defmeta above the defnmalli: :doc and the other keys are var metadata, the [in expected] pairs are the registered cases"
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (not (contains? (meta #'padded) :inout-tests)))
    (is (= {:var `padded :cases 2 :failures []} (inout/check-var #'padded))))
  (testing "defmeta declares the name, so it precedes the definition"
    (is (= '(clojure.core/declare later) (second (macroexpand-1 '(bikes.auction.inout/defmeta later {:doc "x"}))))))
  (testing "a non-map fails at compile time naming the fn"
    (is (re-find #"^defmeta padded: the metadata must be a map literal"
                 (try (pr-str (macroexpand-1 '(bikes.auction.inout/defmeta padded [:doc "x"])))
                      (catch Exception e (ex-message (or (ex-cause e) e)))))))
  (testing "a pair is [in expected]: in is the one argument, a vector in included; anything else throws naming the var"
    (is (= [[[{:a 1}] 3] [[[1 2]] 3]] (inout/single-arg-pairs `padded [[{:a 1} 3] [[1 2] 3]])))
    (doseq [bad [[[1 2 3]] [[1]] [1] '([1 2])]]
      (is (re-find #"padded defmeta cases must be a vector of \[in expected\] pairs"
                   (try (inout/single-arg-pairs `padded bad) nil
                        (catch Exception e (ex-message e))))
          (pr-str bad)))))

(deftest defnmalli-schema-is-instrumented
  (mi/collect! {:ns ['bikes.auction.inout-test]})
  (mi/instrument! {:filters [(mi/-filter-ns 'bikes.auction.inout-test)]})
  (try
    (testing "a good call passes (a defaulted key may be absent: its row is :optional), an unknown key is rejected as :malli.core/invalid-input"
      (is (= 3 (padded {:a 1})))
      (let [data (try (padded {:a 1 :c 3}) nil
                      (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e))))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [{:a 1 :c 3}] (vec (:args (:data data)))))))
    (finally
      (mi/unstrument! {:filters [(mi/-filter-ns 'bikes.auction.inout-test)]}))))
