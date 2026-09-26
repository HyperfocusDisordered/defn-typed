(ns defn-typed.core
  "Input/output cases and field-table defaults for functions, written next to the function:

     (defmeta f
       {:doc \"What f returns.\"
        :inout-tests [[{}     1]                      ; in/out cases: one [in expected] pair each,
                      [{:k 2} 2]]})                   ; in = f's one argument

     (defn-typed f
       {:k [:int {:default 1}]} -> :any               ; the input map's rows, a default in its type's props

       ...k...)                                       ; every row key is a local of the same name

   `defmeta` (above f) expands to (declare f) + the registration of its cases and keys, and leaves
   its map for the defn-typed below, which puts :doc and the other keys into f's attr-map.
   `defn-typed` turns the input map into [:map …] and expands to (def f-props [:map …]),
   (defn f--positional [k] ...) and (defn f {:malli/schema [:=> [:cat f-props] :any]
   :arglists '([{:keys [k]}])} [m] (let [{:keys [k] :or {k 1}} m] (f--positional k))); every call
   site goes through expand-call.
   The macro marks a row with a default `:optional`: instrumentation checks the call before the
   defaults are filled. Callers require both unprefixed: (:require [defn-typed.core :refer [defn-typed defmeta]]).
   A defmeta case passes iff (= expected (f in)). The legacy sources, `tests` and an attr-map
   `{:inout-tests [[[args…] expected] …]}`, keep [[args…] expected] pairs, (= expected (apply f args));
   registered cases win. Works in clj and cljs; this namespace never loads malli: `:malli/schema`
   is plain var metadata until a dev/test/REPL loader runs malli.instrument collect! + instrument!.
   - `check-var` / `check-ns` return data (any REPL, including the browser runtime);
   - `deftests!` (clj) defines one clojure.test test `<ns>--<fn>-inout` per such var so run-tests
     and CI see them;
   - `test-var!` (clj) runs a var's `:test` fn and its cases under one clojure.test report."
  #?(:clj (:require [clojure.test :as test])
     :cljs (:require-macros [defn-typed.core])))

(defn var-name [v] (symbol v))

(defn- pairs-check!
  "Throws, naming sym, unless pairs is nil (no cases) or a vector of [[args…] expected] pairs."
  [sym pairs]
  (let [pair? #(and (vector? %) (= 2 (count %)) (vector? (first %)))
        bad (cond (nil? pairs) nil
                  (vector? pairs) (some #(when-not (pair? %) [%]) pairs)
                  :else [pairs])]
    (when bad
      (throw (ex-info (str "defn-typed: " sym " cases must be a vector of [[args…] expected] pairs, got "
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
      (throw (ex-info (str "defn-typed: " sym " defmeta cases must be a vector of [in expected] pairs, got "
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

(defn print-err!
  "Prints text as one line to stderr (cljs: console.error)."
  [text]
  #?(:clj (binding [*out* *err*] (println text))
     :cljs (js/console.error text)))

(defonce ^{:doc "The fn that builds the checker of a `:malli-in-prod` function from its slot's spec, or nil.
   `defn-typed.malli-in-prod` sets it when it loads; this namespace never loads malli."}
  malli-checker-builder
  (atom nil))

(defn malli-in-prod-slot
  "The holder a `:malli-in-prod` function checks through: its spec `{:fn :input :output :opts}`,
   then the checker built from it on the first call (malli-checker)."
  [spec]
  (atom {:spec spec}))

(defn- build-malli-checker!
  "malli-checker's first-call path: builds slot's checker with malli-checker-builder once it is set;
   nil while it is not (one stderr line per function) or when building failed (one stderr line)."
  [slot {:keys [spec warned failed]}]
  (if-let [build @malli-checker-builder]
    (when-not failed
      (try (let [built (build spec)]
             (swap! slot assoc :checker built)
             built)
           (catch #?(:clj Throwable :cljs :default) e
             (swap! slot assoc :failed true)
             (print-err! (str "defn-typed: :malli-in-prod on " (:fn spec) " is off, its schemas did not compile: "
                              (ex-message e)))
             nil)))
    (do (when-not warned
          (swap! slot assoc :warned true)
          (print-err! (str "defn-typed: :malli-in-prod on " (:fn spec)
                           " but defn-typed.malli-in-prod is not loaded")))
        nil)))

(defn malli-checker
  "slot's checker, built by malli-checker-builder on the first call made once it is set, which
   compiles the validators once per function. nil while `defn-typed.malli-in-prod` is not loaded
   (one stderr line per function) or when building failed (one stderr line): the call then runs
   unchecked."
  [slot]
  (let [state @slot]
    (or (:checker state) (build-malli-checker! slot state))))

(defn malli-check?
  "Whether this call of a `:malli-in-prod` function is checked: a checker, and no `:sample` or the
   checker's sampled? draw falls under it."
  [checker]
  (boolean (and checker (or (nil? (:sample checker)) ((:sampled? checker))))))

(defn malli-check!
  "Checks value (`:input` the call's map, `:output` its result) with checker; nil. The checker never
   throws and reports a violation off the call path."
  [checker direction value]
  ((:check! checker) direction value))

(defn malli-in-prod-opts-problem
  "Why v cannot be a defmeta's `:malli-in-prod` (true, false, or a map literal with optional
   `:sample`, a number 0 < x ≤ 1, and `:redact`, a set literal of keys), else nil."
  [v]
  (cond
    (boolean? v) nil
    (not (map? v)) (str "true or a map {:sample 0<x≤1 :redact #{key …}}, got " (pr-str v))
    (seq (dissoc v :sample :redact)) (str "takes :sample and :redact, got " (pr-str (keys (dissoc v :sample :redact))))
    (and (contains? v :sample) (not (and (number? (:sample v)) (< 0 (:sample v)) (<= (:sample v) 1))))
    (str ":sample is a number 0 < x ≤ 1, got " (pr-str (:sample v)))
    (and (contains? v :redact) (not (set? (:redact v))))
    (str ":redact is a set of keys, got " (pr-str (:redact v)))
    :else nil))

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

(defn schema-props
  "A schema's own props, the map right after its type (`[:int {:min 1 :default 1}]` → `{:min 1
   :default 1}`); nil for a schema without them (`:int`, `[:maybe :int]`)."
  [schema]
  (let [props (when (vector? schema) (second schema))]
    (when (map? props) props)))

(defn entry-default-paths
  "Key paths of the `[:map …]` rows of schema (nested maps included) that carry `:default` in their
   entry props (`[k {:default v} type]`): a default lives only in the type's own props."
  [schema]
  (if (and (vector? schema) (= :map (first schema)))
    (vec (mapcat (fn [row]
                   (let [[k row-props type] (row-parts row)]
                     (concat (when (contains? row-props :default) [[k]])
                             (map #(into [k] %) (entry-default-paths type)))))
                 (filter vector? (rest schema))))
    []))

(def entry-default-rule "put :default into the schema's props: [:int {:default v}]")

(declare defaults-optional)

(defn optional-if-defaulted
  "A `[:map …]` row marked `{:optional true}` when its type carries `:default` in its own props,
   its type's own rows marked the same way (defaults-optional). A defn-typed evaluates it at the
   def for a row whose type holds a symbol or a call, where only the value shows the default."
  [row]
  (let [[k row-props type] (row-parts row)
        row-props (cond-> row-props
                    (contains? (schema-props type) :default) (assoc :optional true))]
    (if row-props [k row-props (defaults-optional type)] [k (defaults-optional type)])))

(defn defaults-optional
  "schema with every `[:map …]` row whose type carries `:default` in its own props marked
   `{:optional true}` (nested maps included): instrumentation checks a call before with-defaults
   fills the defaults, so a defaulted key may be absent."
  [schema]
  (if (and (vector? schema) (= :map (first schema)))
    (into [:map] (map #(if (vector? %) (optional-if-defaulted %) %)) (rest schema))
    schema))

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
   (defn- constant-form?
     "Whether form is data that evaluates to itself: no symbol, no call, at any depth."
     [form]
     (not-any? #(or (symbol? %) (seq? %)) (tree-seq coll? seq form))))

#?(:clj
   (defn- self-recur?
     "Whether form holds a `recur` whose target is the enclosing function: one outside a nested
      loop, fn or letfn binding, and outside a quote."
     [form]
     (cond
       (= 'recur form) true
       (seq? form) (let [head (when (symbol? (first form)) (symbol (name (first form))))]
                     (cond
                       ('#{loop loop* fn fn* quote} head) false
                       (= 'letfn head) (boolean (some self-recur? (nnext form)))
                       :else (boolean (some self-recur? form))))
       (map? form) (boolean (some self-recur? (mapcat identity form)))
       (coll? form) (boolean (some self-recur? form))
       :else false)))

#?(:clj
   (defn- expanded
     "form with every macro call in it expanded, as the compiler will expand it (clj macroexpand-1,
      cljs the analyzer's), so a macro that produces a `recur` shows it; a quoted form stays as
      written. form itself when an expansion throws: the compile reports that error."
     [env form]
     (try
       (let [expand-1 (if (:ns env)
                        (let [cljs-expand-1 @(requiring-resolve 'cljs.analyzer/macroexpand-1)] #(cljs-expand-1 env %))
                        macroexpand-1)
             walk (fn walk [f]
                    (cond
                      (seq? f) (let [e (loop [f f] (let [n (expand-1 f)] (if (identical? n f) f (recur n))))]
                                 (cond (and (seq? e) (= 'quote (first e))) e
                                       (seq? e) (doall (map walk e))
                                       :else (walk e)))
                      (map? f) (into {} (map (fn [[k v]] [(walk k) (walk v)])) f)
                      (vector? f) (mapv walk f)
                      (set? f) (set (map walk f))
                      :else f))]
         (walk form))
       (catch Exception _ form))))

#?(:clj
   (defn- default-slot?
     "Whether a row type form may carry `:default` in its own props: a props map holding it, or a
      symbol or call in the props slot (its value shows only when evaluated)."
     [type]
     (let [slot (when (vector? type) (second type))]
       (or (and (map? slot) (contains? slot :default)) (symbol? slot) (seq? slot)))))

#?(:clj
   (defn- runtime-type?
     "Whether with-defaults can do more to a present value of this row type than the source shows:
      a type that is not a keyword or a vector led by a keyword (a symbol, a call), or a `[:map …]`
      whose rows, at any depth, may carry defaults."
     [type]
     (cond
       (keyword? type) false
       (vector? type) (or (not (keyword? (first type)))
                          (and (= :map (first type))
                               (some (fn [row] (let [[_ _ t] (row-parts row)] (or (default-slot? t) (runtime-type? t))))
                                     (filter vector? (rest type)))))
       :else true)))

#?(:clj
   (defn- default-form
     "The form an absent key of a row takes, evaluated where the call is: the `:default` as written
      when it is data, else a read of the evaluated `props` row at index (a symbol or a call there
      evaluates once, at the def); nil for a row without a default slot."
     [{:keys [type props index]}]
     (let [slot (when (vector? type) (second type))]
       (when (default-slot? type)
         (if (and (map? slot) (constant-form? (:default slot)))
           (:default slot)
           `(:default (schema-props (peek (nth ~props ~index)))))))))

#?(:clj
   (defn- inline-on?
     "Whether a fitting literal call compiles to the positional call: clj — unless the JVM system
      property `defn-typed.inline` is `false` (a dev/test alias sets it, so the call goes through
      the instrumented var); cljs — the compiler's `:optimizations` is `:advanced` (a release build)."
     [cljs?]
     (if cljs?
       (= :advanced (some-> (resolve 'cljs.env/*compiler*) deref deref :options :optimizations))
       (not= "false" (System/getProperty "defn-typed.inline")))))

#?(:clj
   (defonce ^:private instrumentation-warned
     (atom false)))

#?(:clj
   (defn- warn-if-instrumented!
     "Prints one stderr line per JVM when a literal call compiles to the positional call while
      malli.instrument is loaded: instrumentation wraps the var, which that call skips."
     []
     (when (and (find-ns 'malli.instrument) (compare-and-set! instrumentation-warned false true))
       (binding [*out* *err*]
         (println (str "defn-typed: literal calls compile to positional calls, instrumentation will not check them"
                       " — set -Ddefn-typed.inline=false in your dev/test alias"))))))

#?(:clj
   (defn- malli-check-fns
     "{:validate :explain :type :error-message} of malli for the literal value check, or nil: for cljs loaded
      into the compiler's JVM; for clj only when something already loaded malli (a dev/test/REPL
      loader), so a server compiling from source never loads it, switch on or off."
     [cljs?]
     (when (or cljs? (find-ns 'malli.core))
       (try {:validate (requiring-resolve 'malli.core/validate)
             :explain (requiring-resolve 'malli.core/explain)
             :type (requiring-resolve 'malli.core/type)
             :error-message (requiring-resolve 'malli.error/error-message)}
            (catch Exception _ nil)))))

#?(:clj
   (defn- js-number-literal
     "A cljs literal value as the JVM reads it, with every integer-valued double (`1.0`) a long:
      in JS both are the same number, which `:int` accepts."
     [v]
     (cond
       (and (double? v) (== v (Math/rint v)) (< (Math/abs (double v)) 9.007199254740992E15)) (long v)
       (map? v) (into {} (map (fn [[k x]] [(js-number-literal k) (js-number-literal x)])) v)
       (vector? v) (mapv js-number-literal v)
       (set? v) (set (map js-number-literal v))
       :else v)))

#?(:clj
   (defn- literal-errors
     "malli's errors for a literal value against schema, none when it fits. A cljs literal is judged
      as JS numbers, at any depth: integer-valued doubles read as longs (js-number-literal), and a
      number failing a double schema (`:double`, `:float`, `double?`, `float?`) is judged again as a
      double, so `1` fits `:double` and `[:double {:min 2}]` against `1` reads `should be at least 2`."
     [{:keys [malli schema value cljs?]}]
     (let [value (if cljs? (js-number-literal value) value)]
       (when-not ((:validate malli) schema value)
         (cond->> (:errors ((:explain malli) schema value))
           cljs? (mapcat (fn [{:keys [in schema] :as error}]
                           (if (and (number? (:value error)) ('#{:double :float double? float?} ((:type malli) schema)))
                             (for [e (:errors ((:explain malli) schema (double (:value error))))]
                               (update e :in #(into (vec in) %)))
                             [error]))))))))

#?(:clj
   (defn- mismatch-reason
     "Why a value does not fit, as one text: malli's message for each failing part (errors), led by
      its path inside the value when there is one (`at 1: should be a keyword`); `does not match
      <source>` (the row's schema as written) when malli has no message for a part."
     [{:keys [malli errors source]}]
     (let [texts (for [error errors
                       :let [message ((:error-message malli) error)]]
                   (when (and (string? message) (not= "unknown error" message))
                     (str (when (seq (:in error)) (str "at " (path-text (:in error)) ": ")) message)))]
       (if (and (seq texts) (every? some? texts))
         (apply str (interpose ", " (distinct texts)))
         (str "does not match " (pr-str source))))))

#?(:clj
   (defn- literal-mismatches
     "Why a map-literal argument does not fit the rows, one text per finding: an unknown key (a
      closed input map only: `[:map …]` is open), a missing required key (not judged when a key is
      not a keyword literal: `{k 1}` may hold any key), a constant value (data, see constant-form?) its row schema rejects
      (mismatch-reason). A row whose schema cannot be evaluated here (clj: the
      evaluated props; cljs: the source form when it is data) skips the value check. JVM malli
      judges a cljs literal as JS numbers (literal-errors: `1` fits `:double`, `1.0` fits `:int`)."
     [{:keys [spec arg schema-of malli cljs?]}]
     (let [row-of (into {} (map (juxt :key identity)) (:rows spec))]
       (concat
        (when (:closed spec)
          (for [k (keys arg) :when (and (keyword? k) (not (contains? row-of k)))]
            (str (pr-str k) " — unknown key")))
        (when (every? keyword? (keys arg))
          (for [{:keys [key required]} (:rows spec) :when (and required (not (contains? arg key)))]
            (str (pr-str key) " — missing required key")))
        (when malli
          (for [[k v] arg
                :let [row (row-of k)]
                :when (and row (constant-form? v))
                :let [reason (try (let [schema (schema-of row)]
                                    (when-let [errors (and (some? schema)
                                                           (seq (literal-errors {:malli malli :schema schema :value v :cljs? cljs?})))]
                                      (mismatch-reason {:malli malli :errors errors
                                                        :source (:type row)})))
                                  (catch Exception _ nil))]
                :when reason]
            (str (pr-str k) " " (pr-str v) " — " reason)))))))

#?(:clj
   (defn- literal-call
     "`(let [g v …] (positional …))` for a map-literal argument whose keys are all rows and that
      holds every required row: the values bound in the literal's own order (the order the map
      call would evaluate them in), an absent row given its default form (nil when optional)."
     [{:keys [positional rows]} arg]
     (let [locals (into {} (map (fn [k] [k (gensym (str (name k) "__"))])) (keys arg))]
       `(let [~@(mapcat (fn [[k v]] [(locals k) v]) arg)]
          (~positional ~@(map #(get locals (:key %) (:absent %)) rows))))))

#?(:clj
   (defn expand-call
     "A call site of a defn-typed function, as its call-site expander (clj `:inline`, cljs a macro
      of the same name) compiles it: a map-literal argument is checked (literal-mismatches) and a
      mismatch prints one `WARNING defn-typed <file>:<line>: (f …) <findings>` to stderr; with the
      switch on (inline-on?) a literal that fits becomes the positional call; everything else =
      `fallback`, the normal call of the var. Never throws."
     [{:keys [spec arg fallback cljs? file line]}]
     (try
       (if-not (map? arg)
         fallback
         (let [inline? (inline-on? cljs?)
               schema-of (if cljs?
                           #(when (constant-form? (:type %)) (:type %))
                           (let [props (some-> (find-var (:props spec)) deref)]
                             #(when props (peek (nth props (:index %))))))
               mismatches (seq (literal-mismatches {:spec spec :arg arg :schema-of schema-of
                                                    :malli (malli-check-fns cljs?) :cljs? cljs?}))]
           (when mismatches
             (binding [*out* *err*]
               (println (str "WARNING defn-typed " file ":" line ": (" (name (:name spec)) " …) "
                             (apply str (interpose "; " mismatches))))))
           (if (and inline? (not mismatches) (:positional spec)
                    ;; a key beyond the rows (open map) or not a keyword literal: the map call
                    (every? (set (map :key (:rows spec))) (keys arg)))
             (do (when-not cljs? (warn-if-instrumented!))
                 (literal-call spec arg))
             fallback)))
       (catch Exception _ fallback))))

#?(:clj
   (defn- install-cljs-expander!
     "Makes `name` a macro for the cljs analyzer, beside the fn of that name: interned in the clj
      namespace of the same name (where the analyzer looks up `alias/name` and `ns/name`) and
      recorded as the ns's own `:use-macros` (where it looks up a bare `name` in that ns). Value
      position keeps resolving the fn. Called in a release (`:advanced`) build only: a cached
      analysis holding that `:use-macros` entry needs the clj macro ns, which a fresh JVM lacks, so
      shadow-cljs recompiles such a ns on every build; a dev build keeps its cache and plain calls. A clj var of that name that is not such an expander (a
      .cljc loaded on the JVM) stays untouched: that fn then has no expander."
     [env fn-name spec]
     (let [ns-sym (-> env :ns :name)
           macro-ns (create-ns ns-sym)
           existing (.findInternedVar ^clojure.lang.Namespace macro-ns fn-name)]
       (when (or (nil? existing) (::expander (meta existing)))
         (let [cljs-file (resolve 'cljs.analyzer/*cljs-file*)
               v (intern macro-ns (with-meta fn-name {::expander true})
                         ;; decided per compile: a JVM that ran a release keeps this macro, and its
                         ;; later dev compiles get the plain call, without checks
                         (fn [form env & args]
                           (if (and (= 1 (count args)) (inline-on? true))
                             (expand-call {:spec spec :arg (first args) :fallback form :cljs? true
                                           :file (some-> cljs-file deref)
                                           :line (or (:line (meta form)) (:line env))})
                             form)))]
           (.setMacro ^clojure.lang.Var v)
           (swap! @(resolve 'cljs.env/*compiler*)
                  assoc-in [:cljs.analyzer/namespaces ns-sym :use-macros fn-name] ns-sym))))))

#?(:clj
   (defmacro defn-typed
     "(defn-typed name ^{table-props}? {key schema …} -> <out-schema> body…): a one-arity function of one map.
      The input = the map literal of its rows, one entry per line: `key schema`, or `key [props schema]`
      for a row with props; the table's own props (`{:closed true}`) are the map's reader metadata.
      Wrapped into `[:map …]` and def'd as `<name>-props`.
      The body sees every row key as a local of the same name (`:line-h` → `line-h`), defaults
      filled; `^{:as row}` on the map also binds the whole map as with-defaults fills it (keys beyond
      the rows included, `[:map …]` is open) to `row`. Expands to `(def <name>-props [:map …])`,
      `(defn <name>--positional [k… row?] body…)` (the rows in entry order) and
      `(defn name {:malli/schema [:=> [:cat <name>-props] <out-schema>] :arglists '([{:keys [k…]}])} [m]
      (let [{:keys [k…] :or {k default}} m] (<name>--positional k…)))`, so everything that reads defn
      and :malli/schema sees a plain defn, and doc shows the rows as its arglist.
      A default = `:default` in the row type's own props (`:qty [:int {:default 1}]`), read at
      compile time; a row whose defaults only the evaluated schema shows (runtime-type?) is bound
      through row-value, and `^{:as row}` through with-defaults. Instrumentation checks the call
      before the defaults are filled, so the macro marks a defaulted row `{:optional true}`
      (defaults-optional). Every call site goes through expand-call (clj `:inline`, cljs a macro of
      the same name, release builds only): a map literal is checked at compile time, and with the
      switch on (inline-on?) a fitting literal compiles to the positional call. Its docstring and
      cases go into `defmeta` above it. `:default` in a row's entry props is a compile error."
     ([] (throw (ex-info "defn-typed: the first argument must be the function's name, got nothing" {})))
     ([fn-name & more]
      (let [fail! #(throw (ex-info (str "defn-typed " fn-name ": " %) {:fn fn-name}))
            _ (when-not (symbol? fn-name)
                (fail! "the first argument must be the function's name"))
            _ (when (string? (first more))
                (fail! "docstring goes to defmeta"))
            [input [arrow & after-arrow]] (split-with #(not= '-> %) more)
            [out-schema & body] after-arrow]
        (when-not (= '-> arrow)
          (fail! "expected -> between the input rows and the output schema"))
        (when (empty? after-arrow)
          (fail! "no output schema after ->"))
        (when (empty? input)
          (fail! "no input rows between the name and ->"))
        (when (arg-vector? body)
          (fail! (str "args are bound from the rows: drop the argument vector " (pr-str (first body)))))
        (let [table (table-rows fail! input)
              _ (when-let [paths (seq (entry-default-paths table))]
                  (fail! (str (apply str (interpose ", " (map path-text paths))) " · " entry-default-rule)))
              in-schema (defaults-optional table)
              ;; a row whose type holds a symbol or a call shows its default only when evaluated
              props-form (into [] (map #(if (and (vector? %) (not (constant-form? (peek %))))
                                          `(optional-if-defaulted ~%)
                                          %))
                               in-schema)
              ;; ^{:as sym} on the input map = Clojure's own :as: sym = the whole defaults-filled map
              whole (:as (meta (first input)))
              _ (when-not (or (nil? whole) (simple-symbol? whole))
                  (fail! (str "^{:as sym} takes a plain symbol, got " (pr-str whole))))
              props (symbol (str fn-name "-props"))
              positional (symbol (str fn-name "--positional"))
              cljs? (boolean (:ns &env))
              q (qualified &env fn-name)
              q-props (qualified &env props)
              offset (if (map? (second in-schema)) 2 1)
              rows (vec (map-indexed
                         (fn [i row]
                           (let [[k row-props type] (row-parts row)
                                 plan {:key k :local (symbol (name k)) :binding (symbol (namespace k) (name k))
                                       :type type :props q-props :index (+ offset i)}]
                             (cond-> (assoc plan :runtime (runtime-type? type))
                               (default-slot? type) (assoc :absent (default-form plan))
                               (and (not (default-slot? type)) (:optional row-props)) (assoc :absent nil)
                               ;; a type written as a symbol or a call may carry a default: not judged
                               (not (or (default-slot? type) (:optional row-props) (symbol? type) (seq? type)))
                               (assoc :required true))))
                         (filter vector? (rest in-schema))))
              locals (cond-> (mapv :local rows) whole (conj whole))
              _ (when-let [[local keys] (first (filter #(next (val %))
                                                       (group-by :local (cond-> (mapv #(select-keys % [:local :key]) rows)
                                                                          whole (conj {:local whole :key (symbol (str "^{:as " whole "}"))})))))]
                  (fail! (str (apply str (interpose " and " (map (comp pr-str :key) keys))) " both bind " local)))
              ;; clojure fns take at most 20 positional params: a larger table keeps its body in name;
              ;; so does a body whose recur targets the function (it recurs with the map)
              positional? (and (<= (count locals) 20) (not-any? #(self-recur? (expanded &env %)) body))
              m (gensym "m")
              bindings (if whole
                         [{:keys (mapv :binding rows) :as whole} `(with-defaults ~props ~m)]
                         (into [(let [static (remove :runtime rows)
                                      ;; an optional row without a default reads nil either way
                                      defaults (into {} (keep #(when (some? (:absent %)) [(:local %) (:absent %)]))
                                                     static)]
                                  (cond-> {:keys (mapv :binding static)} (seq defaults) (assoc :or defaults)))
                                m]
                               (mapcat #(vector (:local %) `(row-value (nth ~props ~(:index %)) ~m))
                                       (filter :runtime rows))))
              ;; the defmeta written above: its keys other than the cases go into the defn's attr-map,
              ;; so :doc is the var's docstring in clj and cljs alike
              meta-keys (dissoc (get @pending-meta q) :inout-tests)
              malli-opts (let [v (:malli-in-prod meta-keys)] (when (and v (not= false v)) v))
              spec (cond-> {:name q :props q-props :closed (true? (:closed (schema-props in-schema)))
                            :rows (mapv #(select-keys % [:key :index :type :absent :required]) rows)}
                     ;; a :malli-in-prod call must pass the check in name: never the positional call
                     (and positional? (not whole) (not-any? :runtime rows) (not malli-opts))
                     (assoc :positional (qualified &env positional)))
              ;; the map's rows as a destructuring arglist, so doc and editors show the inputs, not m
              arglists (list 'quote (list [(cond-> {:keys (mapv :binding rows)} whole (assoc :as whole))]))
              attrs (cond-> (merge {:arglists arglists} meta-keys {:malli/schema [:=> [:cat props] out-schema]})
                      (not cljs?) (assoc :inline-arities #{1}
                                         ;; the fallback is a host call on the var's value: no op
                                         ;; position, so it is never expanded again
                                         :inline `(fn [arg#]
                                                    (expand-call {:spec '~spec :arg arg# :cljs? false
                                                                  :fallback (list '.invoke (with-meta '~q {:tag 'clojure.lang.IFn}) arg#)
                                                                  :file *file* :line (deref clojure.lang.Compiler/LINE)}))))]
          (swap! pending-meta dissoc q)
          ;; cljs: a release build only (see install-cljs-expander!); a dev build keeps plain calls
          (when (and cljs? (inline-on? true)) (install-cljs-expander! &env fn-name spec))
          (let [call (if positional? `(let ~bindings (~positional ~@locals)) `(let ~bindings ~@body))
                body-defn (when positional? `(defn ~positional {:no-doc true} ~locals ~@body))]
            (if-not malli-opts
              `(do
                 (def ~props ~props-form)
                 ;; the body calls name before its defn: a recursive call, name passed as a value
                 ~@(when positional? [`(declare ~fn-name) body-defn])
                 (defn ~fn-name ~attrs [~m]
                   ~call))
              ;; :malli-in-prod: name checks the map and the result around the body, which lives in
              ;; <name>--positional or <name>--body (a recur there recurs with the map, unchecked)
              (let [slot (symbol (str fn-name "--malli-in-prod"))
                    body-fn (symbol (str fn-name "--body"))
                    checker (gensym "checker")
                    check? (gensym "check")
                    result (gensym "result")]
                `(do
                   (def ~props ~props-form)
                   (def ~(with-meta slot {:no-doc true})
                     (malli-in-prod-slot {:fn '~q :input ~props :output ~out-schema :opts '~malli-opts}))
                   (declare ~fn-name)
                   ~(or body-defn `(defn ~body-fn {:no-doc true} [~m] ~call))
                   (defn ~fn-name ~attrs [~m]
                     (let [~checker (malli-checker ~slot)
                           ~check? (malli-check? ~checker)]
                       (when ~check? (malli-check! ~checker :input ~m))
                       (let [~result ~(if positional? call `(~body-fn ~m))]
                         (when ~check? (malli-check! ~checker :output ~result))
                         ~result))))))))))))

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
       (when-let [problem (and (contains? m :malli-in-prod) (malli-in-prod-opts-problem (:malli-in-prod m)))]
         (fail! (str ":malli-in-prod is " problem)))
       (swap! pending-meta assoc (qualified &env fn-name) m)
       (let [other (dissoc m :inout-tests)
             q (qualified &env fn-name)]
         `(do
            (declare ~fn-name)
            ~@(when (seq other)
                [(dev `(register-meta! (var ~fn-name) ~other))])
            ~@(when (contains? m :inout-tests)
                [(dev `(register-tests! (var ~fn-name) (single-arg-pairs '~q ~(:inout-tests m))))]))))))

(declare with-defaults)

(defn- fill-row
  "m with row's key filled as with-defaults fills it: absent → the `:default` of the row type's own
   props (when it has one), a map value of a `[:map …]` row → filled the same way, else unchanged."
  [m row]
  (let [[k _ type] (row-parts row)
        type-props (schema-props type)]
    (cond
      (not (contains? m k))
      (cond-> m (contains? type-props :default) (assoc k (:default type-props)))

      (and (vector? type) (= :map (first type)) (map? (get m k)))
      (update m k #(with-defaults type %))

      :else m)))

(defn with-defaults
  "props with the default of every `[:map …]` row whose key props lacks, the `:default` of the
   row type's own props (`[:a [:int {:default 1}]]`); a present key, explicit nil included, is
   kept. Row = [k type] or [k row-props type]; a row whose type is a `[:map …]` vector and whose
   value in props is a map is filled the same way. nil props = {}."
  [schema props]
  (reduce fill-row (or props {}) (filter vector? (rest schema))))

(defn row-value
  "The value with-defaults gives row's key of m: what a defn-typed binds for a row whose defaults
   only the evaluated schema shows (a symbol or a call as its type, nested defaults)."
  [row m]
  (get (fill-row m row) (first row)))

;; qualified: cljs resolves a macro of the ns being compiled only through its ns name
(defn-typed.core/tests #'with-defaults
  [[[[:map [:a {:optional true} [:int {:default 1}]]] {}]                          {:a 1}]
   [[[:map [:a [:int {:min 0 :default 1}]]] {:a 2}]                              {:a 2}]
   [[[:map [:a [:maybe {:default 1} :int]]] {:a nil}]                            {:a nil}]
   [[[:map [:n [:map [:b [:string {:default "x"}]]]]] {:n {}}]                   {:n {:b "x"}}]
   [[[:map [:a {:default 1} :int]] {}]                                           {}]
   [[[:map {:closed true} [:a :int] [:b {:optional true} :int]] nil]             {}]])

(defn-typed.core/tests #'schema-props
  [[[[:int {:min 1 :default 1}]] {:min 1 :default 1}]
   [[:int]                        nil]
   [[[:maybe :int]]               nil]])

(defn-typed.core/tests #'entry-default-paths
  [[[[:map [:a {:default 1} :int] [:b [:int {:default 2}]]]]  [[:a]]]
   [[[:map [:n [:map [:c {:default "x"} :string]]]]]         [[:n :c]]]
   [[:int]                                                   []]])

(defn-typed.core/tests #'defaults-optional
  [[[[:map {:closed true} [:a :int] [:b [:int {:default 2}]]]]
    [:map {:closed true} [:a :int] [:b {:optional true} [:int {:default 2}]]]]
   [[[:map [:c {:optional true} [:maybe :int]]]]
    [:map [:c {:optional true} [:maybe :int]]]]
   [[[:map [:n [:map [:b [:string {:default "x"}]]]]]]
    [:map [:n [:map [:b {:optional true} [:string {:default "x"}]]]]]]
   [[:int]                                    :int]])

(defn malli-reasons
  "`<key path> · <message> · got <value>` (∨ `· missing`), one line per failing key, for the ex-data
   of a :malli.core/invalid-input ∨ :malli.core/invalid-output; nil for any other ex-data.
   defn-typed.core carries no malli: the caller passes {:explain malli.core/explain
   :error-message malli.error/error-message}."
  [{:keys [explain error-message]} {:keys [type data]}]
  (let [[schema value single-arg?]
        (case type
          :malli.core/invalid-input [(:input data) (:args data) (= 1 (count (:args data)))]
          :malli.core/invalid-output [(:output data) (:value data) false]
          ;; any other exception carries no malli explanation
          nil)]
    (when schema
      (vec (for [{:keys [in value] :as error} (:errors (explain schema value))
                 :let [path (if single-arg? (vec (rest in)) (vec in))
                       missing? (= :malli.core/missing-key (:type error))]]
             (str (path-text path) " · " (error-message error)
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
           :error-message (requiring-resolve 'malli.error/error-message)}
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
     "Defines a test `<ns>--<fn>-inout` in the calling ns for every var of ns-sym with cases: the ns
      in the name, so functions of one name in two namespaces get two tests in the ns collecting them."
     [ns-sym]
     (require ns-sym)
     (doseq [v (case-vars (concat (registered-vars ns-sym) (vals (ns-interns ns-sym))))]
       (var-cases v)
       (let [sym (var-name v)
             test-var (intern *ns*
                              (with-meta (symbol (str (namespace sym) "--" (name sym) "-inout"))
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
