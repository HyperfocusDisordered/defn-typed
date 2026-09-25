(ns defn-typed.core-test
  "Runs the in/out cases written next to defn-typed.core's own functions (one `<fn>-inout` test per
   function with cases). Below them: the pair format itself, what `defn-typed` and `defmeta`
   expand to, and their release form."
  (:require [defn-typed.core :as core :refer [defn-typed defmeta]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.instrument :as mi]))

(core/deftests! 'defn-typed.core)

(defmeta padded
  {:doc "a + b, b defaulting to 2."
   :inout-tests [[{:a 1}      3]
                 [{:a 1 :b 5} 6]]})

(defn-typed padded
  ^{:closed true}
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
    (let [e (try (core/register-tests! #'incremented bad) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (pr-str bad))
      (is (re-find #"incremented" (str (ex-message e))) (pr-str bad))))
  (core/register-tests! #'incremented [[[1] 2]])
  (swap! core/registry dissoc `incremented))

(deftest defn-typed-expansion
  (testing "the input map (its metadata = the table's own props) turns into [:map …], def'd as <name>-props and referenced from :malli/schema"
    (is (= [:map {:closed true} [:a :int] [:b {:optional true} [:int {:default 2}]]] padded-props))
    (is (= [:=> [:cat padded-props] :int] (:malli/schema (meta #'padded))))
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (= [1] (map count (:arglists (meta #'padded))))))
  (testing "a map without metadata turns into a bare [:map …] in entry order; `key [props schema]` is a row with props; every row key is a local, a qualified key by its name"
    (let [expansion (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int :x/b [{:optional true} :int]} -> :any [a b]))]
      (is (= '(def f-props [:map [:a :int] [:x/b {:optional true} :int]]) (second expansion)))
      (is (= '{:keys [a x/b]} (first (second (last (last expansion))))))))
  (testing "a row whose type carries :default gets {:optional true} (nested maps too); `key [{:optional true} schema]` stays as written"
    (is (= '(def f-props [:map [:a {:optional true} [:int {:min 1 :default 1}]]
                          [:c {:optional true} [:maybe :int]]
                          [:n [:map [:d {:optional true} [:string {:default "x"}]]]]])
           (second (macroexpand-1 '(defn-typed.core/defn-typed f {:a [:int {:min 1 :default 1}]
                                                                  :c [{:optional true} [:maybe :int]]
                                                                  :n [:map [:d [:string {:default "x"}]]]} -> :any a))))))
  (testing "a value vector not led by a map is the row's schema"
    (is (= '(def f-props [:map [:a [:maybe :int]]])
           (second (macroexpand-1 '(defn-typed.core/defn-typed f {:a [:maybe :int]} -> :any a))))))
  (testing "a vector returned as the value is the body, not an argument vector"
    (is (some? (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int} -> :any [:div a]))))
    (is (some? (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int} -> :any [comp {:x a}])))))
  (testing "defaults come from the row type's own props; the absent key is filled"
    (is (= 3 (padded {:a 1})))
    (is (= 6 (padded {:a 1 :b 5}))))
  (testing "a docstring, an input that is not one map (a vector, [:map …], a symbol, two forms), no rows, a non-keyword key, a bad [props schema], :default in a row's entry props (nested included), an argument vector, a missing ->, or nothing after ->, fails at compile time naming the fn"
    (doseq [[form message] [['(defn-typed.core/defn-typed f "doc" {:a :int} -> :any a) #"docstring goes to defmeta"]
                            ['(defn-typed.core/defn-typed f [[:a :int]] -> :any a) #"the input is a map: \{key schema …\}"]
                            ['(defn-typed.core/defn-typed f [:map [:a :int]] -> :any a) #"the input is a map"]
                            ['(defn-typed.core/defn-typed f some-props -> :any a) #"the input is a map"]
                            ['(defn-typed.core/defn-typed f {:a :int} {:b :int} -> :any a) #"the input is a map"]
                            ['(defn-typed.core/defn-typed f -> :any a) #"no input rows"]
                            ['(defn-typed.core/defn-typed f {} -> :any a) #"no input rows in the map"]
                            ['(defn-typed.core/defn-typed f ^{:closed true} {} -> :any a) #"no input rows in the map"]
                            ['(defn-typed.core/defn-typed f {"a" :int} -> :any a) #"a key of the input map is a keyword, got \"a\""]
                            ['(defn-typed.core/defn-typed f {:a [{:optional true}]} -> :any a) #"a row with props is :a \[props schema\]"]
                            ['(defn-typed.core/defn-typed f {:a [{:optional true :default 1} :int]} -> :any a) #":a · put :default into the schema's props: \[:int \{:default v\}\]$"]
                            ['(defn-typed.core/defn-typed f {:a [{:default 1} :int] :n [:map [:c {:default 2} :int]]} -> :any a) #":a, :n :c · put :default into the schema's props"]
                            ['(defn-typed.core/defn-typed f {:a :int} -> :any [{:keys [a]}] a) #"args are bound from the rows"]
                            ['(defn-typed.core/defn-typed f {:a :int} -> :any [m] m) #"args are bound from the rows"]
                            ['(defn-typed.core/defn-typed f {:a :int} => :any a) #"expected ->"]
                            ['(defn-typed.core/defn-typed f {:a :int} ->) #"no output schema after ->"]]]
      (is (re-find (re-pattern (str "^defn-typed f: .*" message))
                   (try (pr-str (macroexpand-1 form))
                        (catch Exception e (ex-message (or (ex-cause e) e)))))
          (pr-str form)))))

(deftest defmeta-expansion
  (testing "defmeta above the defn-typed: :doc and the other keys are var metadata, the [in expected] pairs are the registered cases"
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (not (contains? (meta #'padded) :inout-tests)))
    (is (= {:var `padded :cases 2 :failures []} (core/check-var #'padded))))
  (testing "defmeta declares the name, so it precedes the definition"
    (is (= '(clojure.core/declare later) (second (macroexpand-1 '(defn-typed.core/defmeta later {:doc "x"}))))))
  (testing "a non-map fails at compile time naming the fn"
    (is (re-find #"^defmeta padded: the metadata must be a map literal"
                 (try (pr-str (macroexpand-1 '(defn-typed.core/defmeta padded [:doc "x"])))
                      (catch Exception e (ex-message (or (ex-cause e) e)))))))
  (testing "a pair is [in expected]: in is the one argument, a vector in included; anything else throws naming the var"
    (is (= [[[{:a 1}] 3] [[[1 2]] 3]] (core/single-arg-pairs `padded [[{:a 1} 3] [[1 2] 3]])))
    (doseq [bad [[[1 2 3]] [[1]] [1] '([1 2])]]
      (is (re-find #"padded defmeta cases must be a vector of \[in expected\] pairs"
                   (try (core/single-arg-pairs `padded bad) nil
                        (catch Exception e (ex-message e))))
          (pr-str bad)))))

(deftest defn-typed-schema-is-instrumented
  (mi/collect! {:ns ['defn-typed.core-test]})
  (mi/instrument! {:filters [(mi/-filter-ns 'defn-typed.core-test)]})
  (try
    (testing "a good call passes (a defaulted key may be absent: the macro made its row :optional), an unknown key is rejected as :malli.core/invalid-input"
      (is (= 3 (padded {:a 1})))
      (is (= 6 (padded {:a 1 :b 5})))
      (let [data (try (padded {:a 1 :c 3}) nil
                      (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e))))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [{:a 1 :c 3}] (vec (:args (:data data)))))
        (testing "malli-reasons: one `<key path> · <message> · got <value>` line per failing key"
          (is (= [":c · disallowed key · got 3"] (core/malli-reasons (core/malli-fns) data))))))
    (finally
      (mi/unstrument! {:filters [(mi/-filter-ns 'defn-typed.core-test)]}))))

(deftest check-api
  (testing "check-ns = check-var over a namespace's registered vars and its vars carrying cases"
    (is (= {`incremented 2 `padded 2}
           (into {} (map (juxt :var :cases)) (core/check-ns 'defn-typed.core-test)))))
  (testing "registered-vars lists what defmeta registered; undefined-metas names a defmeta nothing defined"
    (is (some #{#'padded} (core/registered-vars 'defn-typed.core-test)))
    (binding [*ns* (the-ns 'defn-typed.core-test)]
      (eval '(defn-typed.core/defmeta nowhere {:doc "x"})))
    (try
      (is (= ['defn-typed.core-test/nowhere] (vec (core/undefined-metas 'defn-typed.core-test))))
      (finally
        (swap! core/registry dissoc 'defn-typed.core-test/nowhere)
        (ns-unmap 'defn-typed.core-test 'nowhere))))
  (testing "forget-ns! drops a namespace's cases, the registered ones and the attr-map ones"
    (binding [*ns* (create-ns 'defn-typed.scratch)]
      (refer-clojure)
      (eval '(do (defn-typed.core/defmeta one {:inout-tests [[1 2]]})

                 (defn one [n] (inc n))
                 (defn two {:inout-tests [[[1] 2]]} [n] (inc n)))))
    (try
      (is (= [1 1] (mapv :cases (core/check-ns 'defn-typed.scratch))))
      (core/forget-ns! 'defn-typed.scratch)
      (is (= [] (core/check-ns 'defn-typed.scratch)))
      (finally (remove-ns 'defn-typed.scratch)))))

(defn- malli-symbols
  "Symbols of form (at any depth) whose namespace is a malli one."
  [form]
  (filterv #(and (symbol? %) (some-> (namespace %) (str/starts-with? "malli")))
           (tree-seq coll? seq form)))

(deftest release-form
  (testing "defn-typed expands to (def <name>-props …) + a plain clojure.core/defn whose :malli/schema is attr-map data: no malli symbol, so nothing loads malli until a dev/test loader collects and instruments"
    (let [expansion (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int} -> :int a))]
      (is (= ['do 'def 'clojure.core/defn] [(first expansion) (first (second expansion)) (first (nth expansion 2))]))
      (is (= [:=> [:cat 'f-props] :int] (:malli/schema (nth (nth expansion 2) 2))))
      (is (= [] (malli-symbols expansion)))))
  (testing "defmeta: in cljs every registration is under goog.DEBUG, so a release build (goog.DEBUG false) drops the cases, the :meta and #'f; in clj it registers at load"
    (let [expand #(@#'defmeta '(defmeta g {}) %1 'g '{:doc "x" :inout-tests [[1 2]]})
          debug-gated? #(and (seq? %) (= 'clojure.core/when (first %)) (= 'goog.DEBUG (second %)))
          cljs (expand {:ns {:name 'app.core}})
          clj (expand nil)]
      (swap! @#'core/pending-meta dissoc 'app.core/g `g)
      (is (= 2 (count (filter debug-gated? cljs))))
      (is (= [] (filter debug-gated? clj)))
      (is (= [] (malli-symbols cljs) (malli-symbols clj))))))
