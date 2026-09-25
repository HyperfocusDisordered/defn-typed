(ns inout.core
  "Input/output cases and field-table defaults for functions, written next to the function:

     (defmeta f
       {:doc \"What f returns.\"
        :inout-tests [[{}     1]                      ; in/out cases: one [in expected] pair each,
                      [{:k 2} 2]]})                   ; in = f's one argument

     (defn-typed f
       {:k [{:optional true :default 1} :int]} -> :any ; the input map's rows, defaults live here only

       ...k...)                                       ; every row key is a local of the same name

   `defmeta` (above f) expands to (declare f) + the registration of its cases and keys, and leaves
   its map for the defn-typed below, which puts :doc and the other keys into f's attr-map.
   `defn-typed` turns the input map into [:map …] and expands to (def f-props [:map …]) and
   (defn f {:malli/schema [:=> [:cat f-props] :any]} [m] (let [{:keys [k]} (with-defaults f-props m)] ...)).
   A row with a default is `:optional`: instrumentation checks the call before the defaults are
   filled. Callers require both unprefixed: (:require [inout.core :refer [defn-typed defmeta]]).
   A defmeta case passes iff (= expected (f in)). The legacy sources, `tests` and an attr-map
   `{:inout-tests [[[args…] expected] …]}`, keep [[args…] expected] pairs, (= expected (apply f args));
   registered cases win. Works in clj and cljs; this namespace never loads malli: `:malli/schema`
   is plain var metadata until a dev/test/REPL loader runs malli.instrument collect! + instrument!.
   - `check-var` / `check-ns` return data (any REPL, including the browser runtime);
   - `deftests!` (clj) defines one clojure.test test per such var so run-tests and CI see them;
   - `test-var!` (clj) runs a var's `:test` fn and its cases under one clojure.test report."
  #?(:clj (:require [clojure.test :as test])
     :cljs (:require-macros [inout.core])))

(defn var-name [v] (symbol v))

(defn- pairs-check!
  "Throws, naming sym, unless pairs is nil (no cases) or a vector of [[args…] expected] pairs."
  [sym pairs]
  (let [pair? #(and (vector? %) (= 2 (count %)) (vector? (first %)))
        bad (cond (nil? pairs) nil
                  (vector? pairs) (some #(when-not (pair? %) [%]) pairs)
                  :else [pairs])]
    (when bad
      (throw (ex-info (str "inout: " sym " cases must be a vector of [[args…] expected] pairs, got "
                           (pr-str (first bad)))
                      {:var sym :case (first bad)})))))

(defonce registry (atom {}))

(defn register-tests!
  "Registers v's [[args…] expected] pairs, replacing earlier ones; called through `tests`."
  [v pairs]
  (let [sym (var-name v)]
    (pairs-check! sym pairs)
    (swap! registry update sym merge {:var v :inout-tests pairs})
    sym))

(defn register-meta!
  "Records v's `defmeta` map (without `:inout-tests`) under `:meta` beside its cases: a cljs var's
   metadata is fixed at compile time, so this is where cljs keeps `:doc` and the other keys."
  [v m]
  (swap! registry update (var-name v) merge {:var v :meta m})
  (var-name v))

(defn single-arg-pairs
  "A `defmeta`'s `:inout-tests` ([in expected] pairs, in = the function's one argument, a vector
   passed as one argument) as the [[args…] expected] pairs `register-tests!` takes. Throws, naming
   sym, unless pairs is a vector of 2-element vectors."
  [sym pairs]
  (let [bad (if (vector? pairs) (some #(when-not (and (vector? %) (= 2 (count %))) [%]) pairs) [pairs])]
    (when bad
      (throw (ex-info (str "inout: " sym " defmeta cases must be a vector of [in expected] pairs, got "
                           (pr-str (first bad)))
                      {:var sym :case (first bad)})))
    (mapv (fn [[in expected]] [[in] expected]) pairs)))

(defn shown-in
  "A case's args as its failure line shows them: the one argument itself (the `in` of a defmeta
   pair), the args vector for a function of several."
  [args]
  (if (= 1 (count args)) (first args) args))

(defn undefined-metas
  "Names of ns-sym's vars that a `defmeta` declared and registered but nothing defined: a defmeta
   whose defn-typed/defn is missing from the file (or misspelt)."
  [ns-sym]
  (sort (for [[sym {:keys [var]}] @registry
              :when (and var (= (namespace sym) (name ns-sym))
                         #?(:clj (not (bound? var)) :cljs (undefined? @var)))]
          sym)))

(defn forget-ns!
  "Drops every case of ns-sym's vars, both sources: the registered `tests` pairs and the attr-map
   `:inout-tests` of its interned vars. The REPL loop calls it right before it reloads ns-sym, so
   the reload re-derives exactly the cases still in the file (a `defn` sets fresh metadata), and a
   function deleted from the source, whose var stays interned, runs no cases."
  [ns-sym]
  (swap! registry (fn [r] (into {} (remove #(= (namespace (key %)) (name ns-sym))) r)))
  #?(:clj (when-let [ns-obj (find-ns ns-sym)]
            (doseq [v (vals (ns-interns ns-obj))
                    :when (contains? (meta v) :inout-tests)]
              (alter-meta! v dissoc :inout-tests))))
  ns-sym)

#?(:clj
   (defmacro tests
     "Legacy: registers v's cases, a vector of [[args…] expected] pairs, for a function of several
      positional args (defmeta's `:inout-tests` is the form for a function of one argument):
      (tests #'ns/f [[[args…] expected] …]). Replaces
      earlier ones. In cljs the registration, and with it the var #'f and its metadata, exists
      only under goog.DEBUG: a release build drops all of it."
     [v pairs]
     (if (:ns &env)
       ;; tag built outside the syntax-quote, which would resolve `boolean` to clojure.core/boolean
       ;; and lose the hint that lets Closure drop the branch
       `(when ~(with-meta 'goog.DEBUG {:tag 'boolean}) (register-tests! ~v ~pairs))
       `(register-tests! ~v ~pairs))))

(defn- row-parts
  "[k row-props type] of a `[:map …]` row, written [k type] or [k row-props type]."
  [row]
  (if (map? (second row)) row [(first row) nil (second row)]))

(defn defaulted-required-keys
  "Key paths of the `[:map …]` rows of schema (nested maps included) that carry `:default` without
   `:optional true`. Instrumentation checks a call before with-defaults fills the defaults, so a
   call that leaves such a key out is rejected although the table gives it a value."
  [schema]
  (if (and (vector? schema) (= :map (first schema)))
    (vec (mapcat (fn [row]
                   (let [[k row-props type] (row-parts row)]
                     (concat (when (and (contains? row-props :default) (not (:optional row-props)))
                               [[k]])
                             (map #(into [k] %) (defaulted-required-keys type)))))
                 (filter vector? (rest schema))))
    []))

(def defaulted-key-rule "defaulted key must be {:optional true …}")

(defn path-text
  "A key path as the loop prints it: `:a :b`; the empty path reads `(value)`."
  [path]
  (if (empty? path) "(value)" (apply str (interpose " " (map pr-str path)))))

#?(:clj
   (def ^:private reader-location-keys
     "Metadata the readers put on a form (tools.reader on cljs maps included): not table props."
     [:line :column :end-line :end-column :file :source]))

#?(:clj
   (defn- table-rows
     "The input of a defn-typed (the one form between the name and `->`) as the `[:map …]` it
      stands for: a map literal `{key schema …}` with keyword keys, where a row that carries props
      is `key [props schema]` (a value vector led by a map); the map's reader metadata holds the
      table's own props (`^{:closed true} {…}`); its `:as` is a binding (whole-map-local), not a
      table prop. Rows keep the map's entry order, which the reader keeps only up to 8 entries (a
      larger literal reads as a hash map). Calls fail! otherwise."
     [fail! input]
     (let [[table] input
           table-props (not-empty (apply dissoc (meta table) :as reader-location-keys))
           row (fn [[k v]]
                 (cond
                   (not (keyword? k)) (fail! (str "a key of the input map is a keyword, got " (pr-str k)))
                   (not (and (vector? v) (map? (first v)))) [k v]
                   (= 2 (count v)) (into [k] v)
                   :else (fail! (str "a row with props is " (pr-str k) " [props schema], got " (pr-str v)))))]
       (cond
         (or (next input) (not (map? table))) (fail! "the input is a map: {key schema …}")
         (empty? table) (fail! "no input rows in the map")
         :else (into (if table-props [:map table-props] [:map]) (map row table))))))

#?(:clj
   (defn- arg-vector?
     "Whether the first body form is an argument vector: a vector of binding forms (symbols,
      destructuring maps) that holds a destructuring map or is followed by more body. A vector
      returned as the value (hiccup `[:div …]`, `[comp {:x 1}]`, `[a b]` as the last form) is not."
     [body]
     (let [form (first body)
           destructuring? #(and (map? %)
                                (every? (fn [k] (or (symbol? k) (#{"keys" "strs" "syms"} (name k)) (#{:as :or} k)))
                                        (keys %)))]
       (and (vector? form) (seq form)
            (every? #(or (symbol? %) (destructuring? %)) form)
            (or (some destructuring? form) (next body))))))

#?(:clj
   (defonce ^:private pending-meta
     (atom {})))

#?(:clj
   (defn- qualified
     "sym qualified by the namespace the macro expands in (clj *ns*, cljs the analyzer's ns)."
     [env sym]
     (symbol (str (if (:ns env) (-> env :ns :name) (ns-name *ns*))) (name sym))))

#?(:clj
   (defmacro defn-typed
     "(defn-typed name ^{table-props}? {key schema …} -> <out-schema> body…): a one-arity function of one map.
      The input = the map literal of its rows, one entry per line: `key schema`, or `key [props schema]`
      for a row with props; the table's own props (`{:closed true}`) are the map's reader metadata.
      Wrapped into `[:map …]` and def'd as `<name>-props`.
      The body sees every row key as a local of the same name (`:line-h` → `line-h`), bound after
      `with-defaults` filled the defaults; `^{:as row}` on the map also binds that whole filled map
      (keys beyond the rows included, `[:map …]` is open) to `row`. Expands to `(def <name>-props [:map …])` and
      `(defn name {:malli/schema [:=> [:cat <name>-props] <out-schema>]} [m] (let [{:keys [k…]}
      (with-defaults <name>-props m)] body…))`, so everything that reads defn and :malli/schema sees a
      plain defn. Its docstring and cases go into `defmeta` under it. Instrumentation checks the call
      before the defaults are filled, so a row carrying `:default` is also `:optional true`."
     [fn-name & more]
     (let [fail! #(throw (ex-info (str "defn-typed " fn-name ": " %) {:fn fn-name}))
           _ (when-not (symbol? fn-name)
               (fail! "the first argument must be the function's name"))
           _ (when (string? (first more))
               (fail! "docstring goes to defmeta"))
           [input [arrow out-schema & body]] (split-with #(not= '-> %) more)]
       (when-not (= '-> arrow)
         (fail! "expected -> between the input rows and the output schema"))
       (when (empty? input)
         (fail! "no input rows between the name and ->"))
       (when (arg-vector? body)
         (fail! (str "args are bound from the rows: drop the argument vector " (pr-str (first body)))))
       (let [in-schema (table-rows fail! input)
             row-keys (map first (filter vector? (rest in-schema)))
             ;; ^{:as sym} on the input map = Clojure's own :as: sym = the whole defaults-filled map
             whole (:as (meta (first input)))
             _ (when-not (or (nil? whole) (simple-symbol? whole))
                 (fail! (str "^{:as sym} takes a plain symbol, got " (pr-str whole))))
             props (symbol (str fn-name "-props"))
             m (gensym "m")
             ;; the defmeta written above: its keys other than the cases go into the defn's attr-map,
             ;; so :doc is the var's docstring in clj and cljs alike
             q (qualified &env fn-name)
             meta-keys (dissoc (get @pending-meta q) :inout-tests)]
         (swap! pending-meta dissoc q)
         (when-let [paths (seq (defaulted-required-keys in-schema))]
           (fail! (str (apply str (interpose ", " (map path-text paths))) " · " defaulted-key-rule)))
         `(do
            (def ~props ~in-schema)
            (defn ~fn-name
              ~(merge meta-keys {:malli/schema [:=> [:cat props] out-schema]})
              [~m]
              (let [~(cond-> {:keys (mapv #(symbol (namespace %) (name %)) row-keys)} whole (assoc :as whole))
                    (with-defaults ~props ~m)]
                ~@body)))))))

#?(:clj
   (defmacro defmeta
     "(defmeta name {…}): everything about the function `name` other than its signature, written
      right ABOVE its defn-typed/defn, one empty line apart. `:inout-tests` = [in expected] pairs, in
      = the function's one argument (single-arg-pairs), registered at load as `tests` does; every
      other key (`:doc` …) goes into the attr-map of the defn-typed below (pending-meta), so it is
      var metadata in clj and cljs. Expands to (declare name) + the registration, so it runs before
      the var is defined; the keys other than the cases are also recorded in `registry` as the var's
      `:meta` (register-meta!), which is where a plain defn below keeps them. In cljs all of it is
      under goog.DEBUG, like the cases."
     [fn-name m]
     (let [fail! #(throw (ex-info (str "defmeta " fn-name ": " %) {:fn fn-name}))
           debug (with-meta 'goog.DEBUG {:tag 'boolean})
           dev (fn [form] (if (:ns &env) `(when ~debug ~form) form))]
       (when-not (symbol? fn-name)
         (fail! "the first argument must be the function's name"))
       (when-not (map? m)
         (fail! (str "the metadata must be a map literal, got " (pr-str m))))
       (swap! pending-meta assoc (qualified &env fn-name) m)
       (let [other (dissoc m :inout-tests)
             q (qualified &env fn-name)]
         `(do
            (declare ~fn-name)
            ~@(when (seq other)
                [(dev `(register-meta! (var ~fn-name) ~other))])
            ~@(when (contains? m :inout-tests)
                [(dev `(register-tests! (var ~fn-name) (single-arg-pairs '~q ~(:inout-tests m))))]))))))

(defn with-defaults
  "props with the `{:default v}` of every `[:map …]` row whose key props lacks (a present key,
   explicit nil included, is kept). Row = [k type] or [k row-props type]; a row whose type is a
   `[:map …]` vector and whose value in props is a map is filled the same way. nil props = {}."
  [schema props]
  (reduce (fn [m row]
            (let [[k row-props type] (row-parts row)]
              (cond
                (not (contains? m k))
                (cond-> m (contains? row-props :default) (assoc k (:default row-props)))

                (and (vector? type) (= :map (first type)) (map? (get m k)))
                (update m k #(with-defaults type %))

                :else m)))
          (or props {})
          (filter vector? (rest schema))))

;; qualified: cljs resolves a macro of the ns being compiled only through its ns name
(inout.core/tests #'with-defaults
  [[[[:map [:a {:default 1} :int]] {}]                                  {:a 1}]
   [[[:map [:a {:default 1} :int]] {:a 2}]                              {:a 2}]
   [[[:map [:a {:default 1} [:maybe :int]]] {:a nil}]                   {:a nil}]
   [[[:map [:n [:map [:b {:default "x"} :string]]]] {:n {}}]            {:n {:b "x"}}]
   [[[:map {:closed true} [:a :int] [:b {:optional true} :int]] nil]    {}]])

(inout.core/tests #'defaulted-required-keys
  [[[[:map [:a {:default 1} :int] [:b {:optional true :default 2} :int]]]  [[:a]]]
   [[[:map [:n [:map [:c {:default "x"} :string]]]]]                      [[:n :c]]]
   [[:int]                                                                []]])

(defn malli-reasons
  "`<key path> · <message> · got <value>` (∨ `· missing`), one line per failing key, for the ex-data
   of a :malli.core/invalid-input ∨ :malli.core/invalid-output; nil for any other ex-data. A key
   missing from a call whose row carries `:default` without `:optional` reads defaulted-key-rule.
   inout carries no malli: the caller passes {:explain malli.core/explain
   :error-message malli.error/error-message :form malli.core/form}."
  [{:keys [explain error-message form]} {:keys [type data]}]
  (let [[schema value single-arg?]
        (case type
          :malli.core/invalid-input [(:input data) (:args data) (= 1 (count (:args data)))]
          :malli.core/invalid-output [(:output data) (:value data) false]
          ;; any other exception carries no malli explanation
          nil)]
    (when schema
      (vec (for [{:keys [in value] :as error} (:errors (explain schema value))
                 :let [path (if single-arg? (vec (rest in)) (vec in))
                       missing? (= :malli.core/missing-key (:type error))
                       defaulted? (and missing?
                                       (some #{[(last in)]} (defaulted-required-keys (form (:schema error)))))]]
             (str (path-text path) " · " (if defaulted? defaulted-key-rule (error-message error))
                  (if missing? " · missing" (str " · got " (pr-str value)))))))))

(defn- case-source
  "v's registered entry when it carries cases (a `defmeta` without `:inout-tests` registers only
   `:meta`), else v's metadata."
  [v]
  (let [entry (get @registry (var-name v))]
    (if (contains? entry :inout-tests) entry (meta v))))

(defn has-cases? [v]
  (contains? (case-source v) :inout-tests))

(defn var-cases
  "[[i args expected] …] of v: registered pairs, else the `:inout-tests` pairs of its metadata."
  [v]
  (let [pairs (:inout-tests (case-source v))]
    (pairs-check! (var-name v) pairs)
    (map-indexed (fn [i [args expected]] [i args expected]) pairs)))

(defn registered-vars [ns-sym]
  (keep (fn [[sym {:keys [var]}]] (when (= (namespace sym) (name ns-sym)) var)) @registry))

(def ^:dynamic *trace-cases*
  "When true, check-var prints `inout-case <ns/fn> <i> <args>` (and flushes) before each case,
   so a caller that gives up on a blocked eval (an editor's after-edit hook) can name the case it
   blocked in."
  false)

(defn check-var
  "{:var sym :cases n :failures [{:i :in :expected :actual}]}; a throwing case
   reports `:actual [:thrown message]` and its ex-data as `:thrown-data`."
  [v]
  (let [f @v
        results (mapv (fn [[i args expected]]
                        (when *trace-cases*
                          (println (str "inout-case " (var-name v) " " i " " (pr-str args)))
                          (flush))
                        (try {:i i :in args :expected expected :actual (apply f args)}
                             (catch #?(:clj Throwable :cljs :default) e
                               {:i i :in args :expected expected
                                :actual [:thrown (ex-message e)] :thrown-data (ex-data e)})))
                      (var-cases v))]
    {:var (var-name v)
     :cases (count results)
     :failures (filterv #(not= (:expected %) (:actual %)) results)}))

(defn case-vars
  "Distinct vars among `vars` that have cases, sorted by name."
  [vars]
  (->> vars
       (filter has-cases?)
       (group-by var-name)
       (sort-by (comp str key))
       (mapv (comp first val))))

(defn check-vars [vars]
  (mapv check-var (case-vars vars)))

#?(:clj
   (defmacro check-ns
     "check-var over ns-sym's registered vars and its vars (private included) carrying `:inout-tests`
      metadata.
      In cljs ns-sym must be a quoted literal, e.g. (check-ns 'my.ns)."
     [ns-sym]
     (if (:ns &env)
       `(check-vars (concat (registered-vars ~ns-sym) (vals (cljs.core/ns-interns ~ns-sym))))
       `(check-vars (concat (registered-vars ~ns-sym) (vals (ns-interns ~ns-sym)))))))

#?(:clj
   (defn malli-fns
     "The malli fns malli-reasons takes, when malli is on the classpath (test, REPL), else nil."
     []
     (try {:explain (requiring-resolve 'malli.core/explain)
           :error-message (requiring-resolve 'malli.error/error-message)
           :form (requiring-resolve 'malli.core/form)}
          (catch Exception _ nil))))

#?(:clj
   (defn assert-cases
     "One clojure.test assertion per case of v. A throwing case reports an error naming the case
      (and, for a malli rejection, the failing keys) and the next case still runs."
     [v]
     (doseq [[i args expected] (var-cases v)]
       (let [label (str (var-name v) " case " i " in=" (pr-str (shown-in args)))
             [actual thrown] (try [(apply @v args) nil] (catch Throwable e [nil e]))]
         (if thrown
           (test/do-report
            {:type :error :expected expected :actual thrown
             :message (apply str label
                             (for [reason (some-> (malli-fns) (malli-reasons (ex-data thrown)))]
                               (str "\n  " reason)))})
           (test/is (= expected actual) label))))))

#?(:clj
   (defn deftests!
     "Defines `<fn>-inout` tests in the calling ns for every var of ns-sym with cases."
     [ns-sym]
     (require ns-sym)
     (doseq [v (case-vars (concat (registered-vars ns-sym) (vals (ns-interns ns-sym))))]
       (var-cases v)
       (let [test-var (intern *ns*
                              (with-meta (symbol (str (name (var-name v)) "-inout"))
                                {:test #(assert-cases v)})
                              nil)]
         (alter-var-root test-var (constantly #(test/test-var test-var)))))))

#?(:clj
   (defn test-ns!
     "Runs ns-sym's clojure.test tests (with its fixtures) and the in/out cases of its vars under
      one report; prints one summary line counting both and returns the clojure.test counters
      plus :tests and :cases. A namespace with neither returns :tests 0 :cases 0: the caller
      says so instead of reporting a green run of nothing."
     [ns-sym]
     (let [ns-obj (the-ns ns-sym)
           tests (filter (comp :test meta) (vals (ns-interns ns-obj)))
           vars (case-vars (concat (registered-vars ns-sym) (vals (ns-interns ns-obj))))
           cases (reduce + 0 (map (comp count var-cases) vars))]
       (binding [test/*report-counters* (ref test/*initial-report-counters*)]
         (test/do-report {:type :begin-test-ns :ns ns-obj})
         (test/test-vars tests)
         (doseq [v vars]
           (binding [test/*testing-vars* (conj test/*testing-vars* v)]
             (assert-cases v)))
         (test/do-report {:type :end-test-ns :ns ns-obj})
         (let [{:keys [pass fail error] :as counters} @test/*report-counters*]
           (println (str "Ran " ns-sym ": " (count tests) " tests, " cases " in/out cases. "
                         (+ pass fail error) " assertions, " fail " failures, " error " errors."))
           (assoc counters :tests (count tests) :cases cases))))))

#?(:clj
   (defn test-var!
     "Runs v's `:test` fn (when present) and its cases; prints one summary line and returns
      the clojure.test counters."
     [v]
     (binding [test/*report-counters* (ref test/*initial-report-counters*)]
       (test/test-vars [v])
       (when (has-cases? v)
         (binding [test/*testing-vars* (conj test/*testing-vars* v)]
           (assert-cases v)))
       (let [{:keys [pass fail error] :as counters} @test/*report-counters*]
         (println (str "Ran " (var-name v) ": :test fn " (if (:test (meta v)) "yes" "no")
                       ", " (if (has-cases? v) (count (var-cases v)) 0) " in/out cases. "
                       (+ pass fail error) " assertions, " fail " failures, "
                       error " errors."))
         counters))))
