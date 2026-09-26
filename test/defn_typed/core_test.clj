(ns defn-typed.core-test
  "Runs the in/out cases written next to defn-typed.core's own functions (one `<ns>--<fn>-inout`
   test per function with cases). Below them: the pair format itself, what `defn-typed` and `defmeta`
   expand to, and their release form."
  (:require [defn-typed.core :as core :refer [defn-typed defnt defmeta]]
            [clj-kondo.core :as kondo]
            [clojure.repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [malli.instrument :as mi]))

(core/deftests! 'defn-typed.core)

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
    (let [e (try (core/register-tests! #'incremented bad) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (pr-str bad))
      (is (re-find #"incremented" (str (ex-message e))) (pr-str bad))))
  (core/register-tests! #'incremented [[[1] 2]])
  (swap! core/registry dissoc `incremented))

(deftest defn-typed-expansion
  (testing "the input map turns into [:map …], def'd as <name>-props and referenced from :malli/schema"
    (is (= [:map [:a :int] [:b {:optional true} [:int {:default 2}]]] padded-props))
    (is (= [:=> [:cat padded-props] :int] (:malli/schema (meta #'padded))))
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (= '([{:keys [a b]}]) (:arglists (meta #'padded)))))
  (testing "the arglist is the rows as a destructuring map, so doc shows the inputs; a qualified key reads as its binding"
    (is (str/includes? (with-out-str (clojure.repl/doc padded)) "([{:keys [a b]}])"))
    (is (= ''([{:keys [a x/b]}])
           (:arglists (nth (nth (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int :x/b :int} -> :any a)) 5) 2)))))
  (testing "a map without metadata turns into a bare [:map …] in entry order; `key [props schema]` is a row with props; every row key is a local, a qualified key by its name"
    (let [expansion (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int :x/b [{:optional true} :int]} -> :any [a b]))]
      (is (= '(def f-props [:map [:a :int] [:x/b {:optional true} :int]]) (second expansion)))
      (is (= '{:keys [a x/b]} (first (second (last (nth expansion 5))))))))
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
  (testing "a docstring, an input that is not one map (a vector, [:map …], a symbol, two forms), metadata on the input map, no rows, a non-keyword key, a bad [props schema], :default in a row's entry props (nested included), an argument vector, a missing ->, or nothing after ->, fails at compile time naming the fn"
    (doseq [[form message] [['(defn-typed.core/defn-typed f "doc" {:a :int} -> :any a) #"docstring goes to defmeta"]
                            ['(defn-typed.core/defn-typed f [[:a :int]] -> :any a) #"the input is a map: \{key schema …\}"]
                            ['(defn-typed.core/defn-typed f [:map [:a :int]] -> :any a) #"the input is a map"]
                            ['(defn-typed.core/defn-typed f some-props -> :any a) #"the input is a map"]
                            ['(defn-typed.core/defn-typed f {:a :int} {:b :int} -> :any a) #"the input is a map"]
                            ['(defn-typed.core/defn-typed f -> :any a) #"no input rows"]
                            ['(defn-typed.core/defn-typed f {} -> :any a) #"no input rows in the map"]
                            ['(defn-typed.core/defn-typed f ^{:as row} {:a :int} -> :any a) #"metadata on the argument table is not supported"]
                            ['(defn-typed.core/defn-typed f ^:private {:a :int} -> :any a) #"metadata on the argument table is not supported"]
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

(def symslot-opts {:default 3})
(def symslot-type [:int {:default 7}])

(defn-typed symslot {:q [:int symslot-opts] :k symslot-type} -> :any
  [q k]
)

(defn-typed cart-total {
  :items [:sequential [:map [:price [:int {:min 1}]] [:qty [:int {:min 1 :default 1}]]]]
} -> :int
  (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))
)

(deftest defaults-inside-sequences-are-optional
  (testing "a map row with a default inside a :sequential / :vector / :maybe row is marked {:optional true} in <name>-props, at any depth"
    (is (= [:map [:items [:sequential [:map [:price [:int {:min 1}]] [:qty {:optional true} [:int {:min 1 :default 1}]]]]]]
           cart-total-props))
    (is (= '(def f-props [:map [:o [:vector [:map [:l [:sequential [:maybe [:map [:q {:optional true} [:int {:default 1}]]]]]]]]]])
           (second (macroexpand-1 '(defn-typed.core/defn-typed f {:o [:vector [:map [:l [:sequential [:maybe [:map [:q [:int {:default 1}]]]]]]]]} -> :any o))))))
  (testing "a row with no default inside is destructured, not read at call time: a predicate symbol (number?, map?) carries no default"
    (doseq [row '[[:maybe number?] [:vector map?] [:sequential [:map [:a :int]]] number?]]
      (let [expansion (macroexpand-1 (list 'defn-typed.core/defn-typed 'f {:a row} '-> :any 'a))]
        (is (not-any? #{`core/row-value} (tree-seq coll? seq expansion))
            (pr-str row)))))
  (testing "a row with a default inside a :sequential row is read at call time"
    (let [expansion (macroexpand-1 '(defn-typed.core/defn-typed f {:a [:sequential [:map [:q [:int {:default 1}]]]]} -> :any a))]
      (is (some #{`core/row-value} (tree-seq coll? seq expansion)))))
  (testing ":default in the entry props of a map row inside a :sequential row fails at compile time naming its path"
    (is (re-find #"^defn-typed f: :items :qty · put :default into the schema's props"
                 (try (pr-str (macroexpand-1 '(defn-typed.core/defn-typed f {:items [:sequential [:map [:qty {:default 1} :int]]]} -> :any items)))
                      (catch Exception e (ex-message (or (ex-cause e) e))))))))

(deftest defn-typed-schema-is-instrumented
  (mi/collect! {:ns ['defn-typed.core-test]})
  (mi/instrument! {:filters [(mi/-filter-ns 'defn-typed.core-test)]})
  (try
    (testing "a good call passes (a defaulted key may be absent: the macro made its row :optional), a real map's key beyond the rows passes (the map is open), a wrong type is rejected as :malli.core/invalid-input"
      (is (= 3 (padded {:a 1})))
      (is (= 6 (padded {:a 1 :b 5})))
      (let [m {:a 1 :c 3}]
        (is (= 3 (padded m))))
      (let [m {:a "x"}
            data (try (padded m) nil
                      (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e))))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [{:a "x"}] (vec (:args (:data data)))))
        (testing "malli-reasons: one `<key path> · <message> · got <value>` line per failing key"
          (is (= [":a · should be an integer · got \"x\""] (core/malli-reasons (core/malli-fns) data))))))
    (testing "an item of a :sequential row may leave its defaulted key out (a real map, instrumented); a wrong item type is rejected naming its path"
      (let [m {:items [{:price 100}]}]
        (is (= 100 (cart-total m))))
      (let [m {:items [{:price 100} {:price "x"}]}
            data (try (cart-total m) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [":items 1 :price · should be an integer · got \"x\""] (core/malli-reasons (core/malli-fns) data)))))
    (testing "a row whose props slot or type is a symbol and carries a default is optional in <name>-props, so an instrumented call may leave it out"
      (is (= [:map [:q {:optional true} [:int {:default 3}]] [:k {:optional true} [:int {:default 7}]]] symslot-props))
      (is (= [3 7] (symslot {}))))
    (finally
      (mi/unstrument! {:filters [(mi/-filter-ns 'defn-typed.core-test)]}))))

(deftest check-api
  (testing "check-ns = check-var over a namespace's registered vars and its vars carrying cases"
    (is (= {`incremented 2 `padded 2 `shorter 2}
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

(deftest deftests-per-namespace
  (testing "deftests! names each test <ns>--<fn>-inout: functions of one name in two namespaces get two tests in the collecting ns, each running its own cases"
    (let [collector (create-ns 'defn-typed.twin-tests)]
      (try
        (binding [*ns* collector]
          (run! core/deftests! '[defn-typed.twin-a defn-typed.twin-b]))
        (is (= '[defn-typed.twin-a--same-inout defn-typed.twin-b--same-inout]
               (sort (keys (ns-interns collector)))))
        (is (= {:test 2 :pass 3 :fail 0 :error 0}
               (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
                 (clojure.test/test-vars (vals (ns-interns collector)))
                 (select-keys @clojure.test/*report-counters* [:test :pass :fail :error]))))
        (finally (remove-ns 'defn-typed.twin-tests))))))

(defn- malli-symbols
  "Symbols of form (at any depth) whose namespace is a malli one."
  [form]
  (filterv #(and (symbol? %) (some-> (namespace %) (str/starts-with? "malli")))
           (tree-seq coll? seq form)))

(deftest release-form
  (testing "defn-typed expands to (def <name>-props …) + (declare <name>) + the positional defn holding the body + (instrumented-before! '<name>) + a plain clojure.core/defn whose :malli/schema is attr-map data + (keep-instrumented! #'<name>) + (signature! #'<name> …): no malli symbol, so nothing loads malli until a dev/test loader collects and instruments"
    (let [expansion (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int} -> :int a))]
      (is (= ['do 'def 'clojure.core/declare 'clojure.core/defn 'defn-typed.core/instrumented-before! 'clojure.core/defn
              'defn-typed.core/keep-instrumented! 'defn-typed.core/signature!]
             (cons (first expansion) (map first (rest expansion)))))
      (is (= `(quote ~(symbol (str *ns*) "f")) (second (nth expansion 4))))
      (is (= '(var f) (second (nth expansion 6))))
      (is (= '(var f) (second (nth expansion 7))))
      (is (= '(clojure.core/declare f) (nth expansion 2)))
      (is (= '(clojure.core/defn f--positional {:no-doc true} [a] a) (nth expansion 3)))
      (is (= [:=> [:cat 'f-props] :int] (:malli/schema (nth (nth expansion 5) 2))))
      (is (= [] (malli-symbols expansion)))))
  (testing "a cljs dev build (not :advanced): no call-site macro is interned, so the ns records no :use-macros of its own and shadow-cljs keeps its cache"
    (let [expansion (@#'defn-typed '(defn-typed h {:a :int} -> :int a) {:ns {:name 'fx1.dev-cljs}}
                     'h '{:a :int} '-> :int 'a)]
      (is (= 'do (first expansion)))
      (is (nil? (find-ns 'fx1.dev-cljs)))))
  (testing "defmeta: in cljs every registration is under goog.DEBUG, so a release build (goog.DEBUG false) drops the cases, the :meta and #'f; in clj it registers at load"
    (let [expand #(@#'defmeta '(defmeta g {}) %1 'g '{:doc "x" :inout-tests [[1 2]]})
          debug-gated? #(and (seq? %) (= 'clojure.core/when (first %)) (= 'goog.DEBUG (second %)))
          cljs (expand {:ns {:name 'app.core}})
          clj (expand nil)]
      (testing "the defn-typed below it runs the cases at load under goog.DEBUG in cljs, plainly in clj"
        (let [inout-call (fn [env] (some #(when (and (seq? %) (some (fn [f] (and (seq? f) (= `core/inout-check! (first f))))
                                                                      (tree-seq seq? seq %)))
                                             %)
                                         (rest (@#'defn-typed '(defn-typed g {:a :int} -> :int a) env 'g '{:a :int} '-> :int 'a))))]
          (is (debug-gated? (inout-call {:ns {:name 'app.core}})))
          (is (= `core/inout-check! (first (inout-call nil))))))
      (swap! @#'core/pending-meta dissoc 'app.core/g `g)
      (is (= 2 (count (filter debug-gated? cljs))))
      (is (= [] (filter debug-gated? clj)))
      (is (= [] (malli-symbols cljs) (malli-symbols clj))))))

(defn- hook-findings
  "[row message] of each clj-kondo finding for code (a refer the snippet leaves unused, row 1, not
   included), linted with the exported hooks only."
  [code]
  (->> (with-in-str (str "(ns k (:require [defn-typed.core :refer [defn-typed defnt defmeta]]))\n" code)
         (kondo/run! {:lint ["-"] :lang :clj :cache false
                      :config-dir "resources/clj-kondo.exports/io.github.hyperfocusdisordered/defn-typed"}))
       :findings
       (remove #(= 1 (:row %)))
       (mapv (juxt :row :message))))

(deftest malformed-forms
  (testing "the clj-kondo hook reports a malformed form as a finding naming the problem, and the file's other findings stay"
    (let [others [3 "k/g is called with 2 args but expects 1"]
          lint #(hook-findings (str "(defn g [x] x)\n(g 1 2)\n" %))]
      (is (= [others [4 "defmeta: the first argument must be the function's name"]]
             (lint "(defmeta 5 {:doc \"x\"})")))
      (is (= [others [4 "defn-typed: the first argument must be the function's name"]]
             (lint "(defn-typed)")))
      (is (= [others [4 "defn-typed: the input map has a key without a schema: {key schema …}"]]
             (lint "(defn-typed f {:a :int :b} -> :any a)")))
      (is (= [others [4 "defn-typed: :x/b and :y/b both bind b"]]
             (lint "(defn-typed f {:x/b :int :y/b :int} -> :any b)")))
      (is (= [others [4 "defn-typed: metadata on the argument table is not supported; declare data as a row, e.g. {:row :map}"]]
             (lint "(defn-typed f ^{:closed true} {:a :int} -> :any a)")))
      (is (= [others [4 "defn-typed: :items :qty · put :default into the schema's props: [:int {:default v}]"]]
             (lint "(defn-typed f {:items [:sequential [:map [:qty {:default 1} :int]]]} -> :any items)")))))
  (testing "the macro throws a compile error naming the same problem (an odd map literal is the reader's error)"
    (let [error #(try (macroexpand-1 %) (catch Exception e (ex-message (or (ex-cause e) e))))]
      (is (= "defmeta 5: the first argument must be the function's name" (error '(defn-typed.core/defmeta 5 {:doc "x"}))))
      (is (= "defn-typed: the first argument must be the function's name, got nothing" (error '(defn-typed.core/defn-typed))))
      (is (= "defn-typed f: :x/b and :y/b both bind b" (error '(defn-typed.core/defn-typed f {:x/b :int :y/b :int} -> :any b))))
      (is (= "defn-typed f: metadata on the argument table is not supported; declare data as a row, e.g. {:row :map}"
             (error '(defn-typed.core/defn-typed f ^{:closed true} {:a :int} -> :any a))))
      (is (= "Map literal must contain an even number of forms"
             (try (read-string "(defn-typed f {:a :int :b} -> :any a)") (catch Exception e (ex-message e))))))))

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

(defn- gensyms-numbered
  "form with every gensym'd symbol (`m1234`, `arg__1234__auto__`) renamed by order of first
   appearance, so two expansions compare equal iff they differ only in gensym numbers."
  [form]
  (let [seen (atom {})]
    (clojure.walk/postwalk
     (fn [x]
       (if-let [[_ prefix] (and (symbol? x) (nil? (namespace x)) (re-matches #"(.*?)\d+(?:__auto__)?" (name x)))]
         (or (@seen x) ((swap! seen assoc x (symbol (str prefix "#" (count @seen)))) x))
         x))
     form)))

(deftest defnt-is-defn-typed
  (testing "(defnt …) expands to exactly what (defn-typed …) of the same form expands to (gensym numbers aside)"
    (let [expand #(binding [*ns* (the-ns 'defn-typed.core-test)]
                    (macroexpand-1 (list* % 'f '({:a :int :b [:int {:default 2}]} -> :int (+ a b)))))
          typed (expand 'defn-typed.core/defn-typed)]
      (is (= (gensyms-numbered typed) (gensyms-numbered (expand 'defn-typed.core/defnt))))
      (is (not= typed (gensyms-numbered typed)) "the expansion holds gensyms: the renaming is what makes them comparable")))
  (testing "a defnt function is a defn-typed function: <name>-props, :malli/schema, the defmeta's :doc and cases, defaults"
    (is (= [:map [:a :int] [:b {:optional true} [:int {:default 2}]]] shorter-props))
    (is (= [:=> [:cat shorter-props] :int] (:malli/schema (meta #'shorter))))
    (is (= "a + b under the short name, b defaulting to 2." (:doc (meta #'shorter))))
    (is (= {:var `shorter :cases 2 :failures []} (core/check-var #'shorter)))
    (is (= 3 (shorter {:a 1}))))
  (testing "defnt is a macro whose compile errors are defn-typed's"
    (is (:macro (meta #'defnt)))
    (let [error #(try (macroexpand-1 %) (catch Exception e (ex-message (or (ex-cause e) e))))]
      (is (= (error '(defn-typed.core/defn-typed)) (error '(defn-typed.core/defnt))))
      (is (= "defn-typed f: :x/b and :y/b both bind b" (error '(defn-typed.core/defnt f {:x/b :int :y/b :int} -> :any b))))))
  (testing "the exported clj-kondo hooks lint (defnt …) as (defn-typed …): the same findings"
    (doseq [form ["(%s f {\"a\" :int} -> :any a)"
                  "(%s f {:a :int} -> :int b)"
                  "(%s f {:a :int} -> :int a)\n(f {:a 1} 2)"]]
      (let [typed (hook-findings (format form "defn-typed"))]
        (is (seq typed) form)
        (is (= typed (hook-findings (format form "defnt"))) form)))))

(deftest cljs-expander-decides-per-compile
  (testing "a JVM that ran a release (the expander interned) and then compiles in dev: the dev call is the plain call, no check, no rewrite"
    (let [compiler (atom {:options {:optimizations :advanced}})
          env {:ns {:name 'n1.shared-jvm}}]
      (intern (create-ns 'cljs.env) '*compiler* compiler)
      (try
        (binding [*ns* (the-ns 'defn-typed.core-test)]
          (@#'defn-typed '(defn-typed total {:qty [:int {:min 1}]} -> :int qty) env
           'total '{:qty [:int {:min 1}]} '-> :int 'qty))
        (let [expander @(ns-resolve 'n1.shared-jvm 'total)
              form '(total {:qty 0})
              expand (fn [] (let [err (java.io.StringWriter.)]
                              [(binding [*err* err] (expander form env {:qty 0})) (str err)]))]
          (is (re-find #"\(total …\) :qty 0 — should be at least 1" (second (expand))) "release: checked")
          (swap! compiler assoc-in [:options :optimizations] :none)
          (is (= [form ""] (expand)) "dev in the same JVM: the plain call, no warning"))
        (finally
          (remove-ns 'cljs.env)
          (remove-ns 'n1.shared-jvm))))))

(deftest typed-check-without-typed-clojure
  (testing "Typed Clojure not on the classpath (this suite): a definition whose body contradicts its output schema prints nothing, emits no check, never loads the bridge"
    (let [err (java.io.StringWriter.)]
      (binding [*err* err
                *ns* (the-ns 'defn-typed.core-test)
                *file* "defn_typed/core_test.clj"]
        (eval '(defn-typed.core/defn-typed mistyped {:n :int} -> :string n)))
      (is (= "" (str err))))
    (is (not-any? #(and (seq? %) (= 'defn-typed.core/typed-check! (first %)))
                  (macroexpand-1 '(defn-typed.core/defn-typed f {:n :int} -> :string n))))
    (is (nil? (find-ns 'defn-typed.typed-clojure)))))
