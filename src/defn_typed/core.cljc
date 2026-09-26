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
   :arglists '([{:keys [k]}])} [m] (let [{:keys [k] :or {k 1}} m] (f--positional k))) and, in clj,
   (instrumented-before! 'f) before that defn and (keep-instrumented! #'f) after it, so a var malli
   instrumented stays instrumented when redefined, (signature! #'f \"…\") and, for rows typed by a
   schema var (`:order Order`), (watch-schemas! #'f [#'Order]); every call site goes through
   expand-call, a redefinition with another signature reloads the namespaces whose calls compiled
   to the old positional call, and a changed schema var reloads the function's namespace
   (`:stale-callers` in defn-typed.edn). With Typed Clojure on the classpath a clj definition then
   calls (typed-check! #'f …), which type-checks it (`:typed-check` in defn-typed.edn).
   The macro marks a row with a default `:optional`: instrumentation checks the call before the
   defaults are filled. Callers require both unprefixed: (:require [defn-typed.core :refer [defn-typed defmeta]]).
   `defnt` = defn-typed under a short name.
   A defmeta case passes iff (= expected (f in)). The legacy sources, `tests` and an attr-map
   `{:inout-tests [[[args…] expected] …]}`, keep [[args…] expected] pairs, (= expected (apply f args));
   registered cases win. Works in clj and cljs; this namespace never loads malli: `:malli/schema`
   is plain var metadata until a dev/test/REPL loader runs malli.instrument collect! + instrument!.
   - `check-var` / `check-ns` return data (any REPL, including the browser runtime);
   - `deftests!` (clj) defines one clojure.test test `<ns>--<fn>-inout` per such var so run-tests
     and CI see them;
   - `test-var!` (clj) runs a var's `:test` fn and its cases under one clojure.test report."
  #?(:clj (:require [clojure.edn]
                   [clojure.string]
                   [clojure.test :as test]
                   [clojure.walk])
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

(def unknown-keys-report-limit
  "How many distinct (fn, set of unknown keys) pairs `:unknown-keys :warn` remembers and prints per
   process, 1000. It exists for memory and log volume: callers that pass generated keys (ids as
   keys, keys built from data) would otherwise grow the remembered set and the log without end.
   Past it, one line says the warnings are muted and nothing more is printed or remembered."
  1000)

(defonce ^{:doc "`{:reported #{[fn #{unknown key …}] …} :muted bool}`: the pairs report-unknown-keys!
   has printed (one line each per process), and whether it stopped at unknown-keys-report-limit
   (then the set is dropped)."}
  unknown-keys-reported
  (atom {:reported #{} :muted false}))

(defn- remember-report
  "state with the pair reported recorded: already known or muted → as it is; under the limit →
   added; at the limit → muted, the pairs dropped."
  [{:keys [reported muted] :as state} pair]
  (cond
    (or muted (contains? reported pair)) state
    (< (count reported) unknown-keys-report-limit) (update state :reported conj pair)
    :else {:reported #{} :muted true}))

(defn report-unknown-keys!
  "Reports the keys of a real map a defn-typed function got that are not rows of its input, as its
   `:unknown-keys` setting (mode) says: `:warn` prints `defn-typed: <ns/fn> unknown key :k — the keys
   are :a :b` once per (fn, set of unknown keys) per process (clj stderr, cljs console.warn), up to
   unknown-keys-report-limit pairs, then one muted line and nothing more; the call proceeds.
   `:error` throws an ex-info of that text with `{:fn :unknown-keys :keys}`, every time."
  [{fn-name :fn row-keys :keys :keys [mode unknown-keys]}]
  (let [text (str "defn-typed: " fn-name " unknown key" (when (next unknown-keys) "s") " "
                  (apply str (interpose " " (map pr-str unknown-keys)))
                  " — the keys are " (apply str (interpose " " (map pr-str row-keys))))]
    (if (= :error mode)
      (throw (ex-info text {:fn fn-name :unknown-keys unknown-keys :keys row-keys}))
      (let [pair [fn-name (set unknown-keys)]
            [before after] (swap-vals! unknown-keys-reported remember-report pair)
            line (cond
                   (and (:muted after) (not (:muted before)))
                   (str "defn-typed: unknown-key warnings muted after " unknown-keys-report-limit " distinct reports")
                   (not= (:reported before) (:reported after)) text
                   ;; known pair, or muted: nothing to print
                   :else nil)]
        (when line
          #?(:clj (binding [*out* *err*] (println line))
             :cljs (js/console.warn line)))))))

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

(defn- items-schema
  "The one schema a `[:sequential …]`, `[:vector …]` or `[:maybe …]` holds (its last child); nil for
   any other schema. These are the schemas the rows of a map inside them are walked through."
  [schema]
  (when (and (vector? schema) (#{:sequential :vector :maybe} (first schema)))
    (peek schema)))

(declare walk-rows)

(defn- walk-row
  "row = [k type] or [k row-props type], its type walked (walk-rows), then f applied to it."
  [f row]
  (let [[k row-props type] (row-parts row)
        type (walk-rows f type)]
    (f (if row-props [k row-props type] [k type]))))

(defn walk-rows
  "schema with f applied to every row of every `[:map …]` inside it, at any depth: the rows of a
   `[:map …]` (each row's type walked first), and the maps a `[:sequential …]`, `[:vector …]` or
   `[:maybe …]` holds. f takes and returns a row, [k type] or [k row-props type]."
  [f schema]
  (cond
    (and (vector? schema) (= :map (first schema)))
    (into [:map] (map #(if (vector? %) (walk-row f %) %)) (rest schema))

    (items-schema schema) (conj (pop schema) (walk-rows f (peek schema)))

    ;; any other schema holds no row walked here
    :else schema))

(defn entry-default-paths
  "Key paths of the `[:map …]` rows of schema, at any depth (the rows walk-rows reaches), that
   carry `:default` in their entry props (`[k {:default v} type]`): a default lives only in the
   type's own props."
  [schema]
  (cond
    (and (vector? schema) (= :map (first schema)))
    (vec (mapcat (fn [row]
                   (let [[k row-props type] (row-parts row)]
                     (concat (when (contains? row-props :default) [[k]])
                             (map #(into [k] %) (entry-default-paths type)))))
                 (filter vector? (rest schema))))

    (items-schema schema) (entry-default-paths (peek schema))

    ;; any other schema holds no rows
    :else []))

(def entry-default-rule "put :default into the schema's props: [:int {:default v}]")

(defn- optional-if-default
  "row marked `{:optional true}` when its type carries `:default` in its own props."
  [row]
  (let [[k row-props type] (row-parts row)]
    (if (contains? (schema-props type) :default)
      [k (assoc row-props :optional true) type]
      row)))

(defn optional-if-defaulted
  "A `[:map …]` row marked `{:optional true}` when its type carries `:default` in its own props,
   its type's own rows marked the same way (defaults-optional). A defn-typed evaluates it at the
   def for a row whose type holds a symbol or a call, where only the value shows the default."
  [row]
  (walk-row optional-if-default row))

(defn defaults-optional
  "schema with every `[:map …]` row whose type carries `:default` in its own props marked
   `{:optional true}`, at any depth (walk-rows: nested maps, the maps of `:sequential`, `:vector`
   and `:maybe`): instrumentation checks a call before the defaults are filled, so a defaulted
   key may be absent."
  [schema]
  (walk-rows optional-if-default schema))

(defn- defaults-inside?
  "Whether a value of schema can hold a key with-defaults fills: a `[:map …]` row whose type
   carries `:default` in its own props, at any depth (the rows walk-rows reaches)."
  [schema]
  (cond
    (and (vector? schema) (= :map (first schema)))
    (boolean (some (fn [row] (let [type (peek row)]
                               (or (contains? (schema-props type) :default) (defaults-inside? type))))
                   (filter vector? (rest schema))))

    (items-schema schema) (defaults-inside? (peek schema))

    ;; any other schema: with-defaults leaves its value as it is
    :else false))

(defn path-text
  "A key path as the loop prints it: `:a :b`; the empty path reads `(value)`."
  [path]
  (if (empty? path) "(value)" (apply str (interpose " " (map pr-str path)))))

#?(:clj
   (def ^:private reader-location-keys
     "Metadata the readers put on a form (tools.reader on cljs maps included): the only metadata
      the input map may carry."
     [:line :column :end-line :end-column :file :source]))

#?(:clj
   (defn- table-rows
     "The input of a defn-typed (the one form between the name and `->`) as the `[:map …]` it
      stands for: a map literal `{key schema …}` with keyword keys, where a row that carries props
      is `key [props schema]` (a value vector led by a map); the map carries no metadata of its
      own (data the body needs whole is a row, `{:row :map}`). Rows keep the map's entry order,
      which the reader keeps only up to 8 entries (a larger literal reads as a hash map). Calls
      fail! otherwise."
     [fail! input]
     (let [[table] input
           row (fn [[k v]]
                 (cond
                   (not (keyword? k)) (fail! (str "a key of the input map is a keyword, got " (pr-str k)))
                   (not (and (vector? v) (map? (first v)))) [k v]
                   (= 2 (count v)) (into [k] v)
                   :else (fail! (str "a row with props is " (pr-str k) " [props schema], got " (pr-str v)))))]
       (cond
         (or (next input) (not (map? table))) (fail! "the input is a map: {key schema …}")
         (seq (apply dissoc (meta table) reader-location-keys))
         (fail! "metadata on the argument table is not supported; declare data as a row, e.g. {:row :map}")
         (empty? table) (fail! "no input rows in the map")
         :else (into [:map] (map row table))))))

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
   (defn- fn-symbol?
     "Whether sym names a function where the macro expands (clj: its var holds a fn; cljs: the
      analyzer knows it as a fn var): a predicate schema such as `number?`, whose value carries no
      default and no rows. false for anything else (a schema held in a var, an unknown symbol)."
     [env sym]
     (try
       (if (:ns env)
         (boolean (:fn-var (@(requiring-resolve 'cljs.analyzer/resolve-var) env sym)))
         (let [v (resolve sym)]
           (boolean (and (var? v) (bound? v) (fn? @v)))))
       (catch Exception _ false))))

#?(:clj
   (defn- runtime-type?
     "Whether with-defaults can do more to a present value of this row type than the source shows:
      a symbol that does not name a function (fn-symbol?) or a call, a vector not led by a keyword,
      or a schema holding, at any depth (the rows walk-rows reaches), a `[:map …]` row whose type
      may carry a default (default-slot?) or is such a type itself."
     [env type]
     (cond
       (keyword? type) false
       (symbol? type) (not (fn-symbol? env type))
       (vector? type) (or (not (keyword? (first type)))
                          (and (= :map (first type))
                               (boolean (some (fn [row] (let [t (peek row)] (or (default-slot? t) (runtime-type? env t))))
                                              (filter vector? (rest type)))))
                          (boolean (some->> (items-schema type) (runtime-type? env))))
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
   (def ^:private setting-values
     "The settings of defn-typed.edn and their allowed values, the first one the default.
      `:literal-check`, `:unknown-keys`, `:typed-check` and `:inout-check` also take a per-function
      value in `defmeta`."
     {:literal-check [:warn :error :off]
      :unknown-keys [:warn :error :off]
      :inline [true false]
      :stale-callers [:reload :warn :off]
      :typed-check [:warn :error :off]
      :inout-check [:warn :error :off]}))

#?(:clj
   (defn- setting-value-problem
     "Why v cannot be the value of the setting k (`<k> must be one of <allowed>, got <v>`), else nil;
      k is one of setting-values."
     [k v]
     (let [allowed (get setting-values k)]
       (when (not-any? #(= v %) allowed)
         (str (pr-str k) " must be one of " (apply str (interpose ", " (map pr-str allowed))) ", got " (pr-str v))))))

#?(:clj
   (defn settings-file
     "The defn-typed.edn that serves dir (a java.io.File): the one in dir, else in its nearest parent
      that has one (the way clj-kondo finds .clj-kondo), up to the filesystem root; nil when none."
     [^java.io.File dir]
     (some #(let [f (java.io.File. ^java.io.File % "defn-typed.edn")] (when (.isFile f) f))
           (take-while some? (iterate #(.getParentFile ^java.io.File %) (.getAbsoluteFile dir))))))

#?(:clj
   (defn settings-of-file
     "The settings a defn-typed.edn file (a java.io.File, or nil for none) gives: the defaults,
      `{:literal-check :warn :unknown-keys :warn :inline true :stale-callers :reload :typed-check :warn
      :inout-check :warn}`,
      merged with the file's map. Throws,
      naming the file, when its content is not a map, holds a key other than those, or a value
      outside the key's allowed ones."
     [^java.io.File file]
     (let [where (str "defn-typed " (some-> file .getPath) ": ")
           m (if file (clojure.edn/read-string (slurp file)) {})]
       (when-not (map? m)
         (throw (ex-info (str where "the settings must be a map, got " (pr-str m)) {:file file})))
       (doseq [[k v] m]
         (cond
           (not (contains? setting-values k))
           (throw (ex-info (str where "unknown key " (pr-str k) " — the keys are "
                                (apply str (interpose ", " (keys setting-values))))
                           {:file file :key k}))
           (setting-value-problem k v)
           (throw (ex-info (str where (setting-value-problem k v)) {:file file :key k}))
           ;; a known key with an allowed value: merged below
           :else nil))
       (merge (into {} (map (fn [[k vs]] [k (first vs)])) setting-values) m))))

#?(:clj
   (defonce ^:private project-settings
     ;; the defn-typed.edn serving the JVM's working directory (where clj and shadow-cljs compile):
     ;; read once per JVM, at the first macroexpansion
     (delay (settings-of-file (settings-file (java.io.File. (System/getProperty "user.dir")))))))

#?(:clj
   (defn- setting
     "A setting's value for the whole project: the JVM system property `defn-typed.<key>` when set
      (`defn-typed.inline`: anything but `false` is on; `defn-typed.literal-check`,
      `defn-typed.unknown-keys`, `defn-typed.typed-check` and `defn-typed.inout-check`: `warn`, `error`
      or `off`; `defn-typed.stale-callers`: `reload`, `warn` or `off`), else the project's
      defn-typed.edn, else the default. A function's own `:literal-check` / `:unknown-keys` /
      `:typed-check` / `:inout-check` in its defmeta wins over it."
     [k]
     (let [property (System/getProperty (str "defn-typed." (name k)))]
       (cond
         (nil? property) (get @project-settings k)
         (= :inline k) (not= "false" property)
         (some #{(keyword property)} (get setting-values k)) (keyword property)
         :else (throw (ex-info (str "defn-typed: the system property defn-typed." (name k)
                                    " must be one of " (apply str (interpose ", " (map name (get setting-values k))))
                                    ", got " (pr-str property))
                               {:property (str "defn-typed." (name k))}))))))

#?(:clj
   (defn- inline-on?
     "Whether a fitting literal call compiles to the positional call: clj — the `:inline` setting
      (a dev/test alias sets `-Ddefn-typed.inline=false`, so the call goes through the instrumented
      var); cljs — the compiler's `:optimizations` is `:advanced` (a release build)."
     [cljs?]
     (if cljs?
       (= :advanced (some-> (resolve 'cljs.env/*compiler*) deref deref :options :optimizations))
       (setting :inline))))

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
   (defonce ^{:doc "`{<fn> #{<caller ns> …}}`: for each defn-typed function (qualified symbol), the
      namespaces whose file holds a literal call compiled to its positional call, in this process.
      signature! drops a namespace from every entry before it reloads it, and the reload records
      the calls it compiles again."}
     inlined-callers
     (atom {})))

#?(:clj
   (defn- record-inlined-caller!
     "Records *ns* as a caller of f (a qualified symbol) in inlined-callers when the positional call
      compiles from a file: a call typed into a REPL (file `NO_SOURCE_PATH`) has no file to reload."
     [f file]
     (when (and file (not= "NO_SOURCE_PATH" file))
       (swap! inlined-callers update f (fnil conj #{}) (ns-name *ns*)))))

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
     (let [value (if cljs? (js-number-literal value) value)
           ;; a defaulted key may be absent: the value is judged before with-defaults fills it
           schema (defaults-optional schema)]
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
   (defn- literal-items
     "The item forms of a sequence literal: a vector literal's items, a quoted list's items; nil for
      any other form."
     [form]
     (cond
       (vector? form) form
       (and (seq? form) (= 'quote (first form)) (seq? (second form))) (vec (second form))
       ;; not a sequence literal
       :else nil)))

#?(:clj
   (defn- map-schema
     "The `[:map …]` schema a map literal is judged against: schema itself, or the one inside
      `[:maybe …]`; nil for any other schema."
     [schema]
     (when (vector? schema)
       (case (first schema)
         :map schema
         :maybe (map-schema (peek schema))
         ;; any other schema: malli judges the value whole
         nil))))

#?(:clj
   (defn- item-schema
     "The item schema a sequence literal's items are judged against one by one: the schema of
      `[:sequential …]` / `[:vector …]` (or one inside `[:maybe …]`) when a map schema is inside it
      at some depth; nil for any other schema (malli judges the value whole)."
     [schema]
     (when (vector? schema)
       (case (first schema)
         (:sequential :vector) (let [item (peek schema)]
                                 (when (or (map-schema item) (item-schema item)) item))
         :maybe (item-schema (peek schema))
         ;; any other schema: malli judges the value whole
         nil))))

#?(:clj
   (declare ^:private map-literal-mismatches))

#?(:clj
   (defn- value-mismatches
     "Why the literal value at path does not fit schema, one text per finding: a map literal
      against a `[:map …]` (map-schema) → the findings inside it (map-literal-mismatches); a
      vector literal or quoted list against a sequence of maps (item-schema) → each item's, its
      index in the path; other data (constant-form?) → `<path> <value> — <reason>`
      (mismatch-reason) when malli is loaded; a symbol, a call, or no schema → none. JVM malli
      judges a cljs literal as JS numbers (literal-errors: `1` fits `:double`, `1.0` fits `:int`)."
     [{:keys [path value schema source malli cljs?]}]
     (let [nested (map-schema schema)
           item (item-schema schema)
           items (literal-items value)]
       (cond
         (and nested (map? value))
         (map-literal-mismatches {:path path :m value :malli malli :cljs? cljs?
                                  :rows (for [entry (rest nested) :when (vector? entry)
                                              :let [[k entry-props type] (row-parts entry)]]
                                          ;; a defaulted key, or one whose type is a symbol or a call
                                          ;; (it may carry a default), may be absent
                                          {:key k :schema type :source type
                                           :required (not (or (:optional entry-props) (contains? (schema-props type) :default)
                                                              (symbol? type) (seq? type)))})})

         (and item items)
         (apply concat (map-indexed (fn [i v]
                                      (value-mismatches {:path (conj path i) :value v :schema item :source item
                                                         :malli malli :cljs? cljs?}))
                                    items))

         (and malli (some? schema) (constant-form? value))
         (when-let [errors (try (seq (literal-errors {:malli malli :schema schema :value value :cljs? cljs?}))
                                (catch Exception _ nil))]
           [(str (path-text path) " " (pr-str value) " — "
                 (mismatch-reason {:malli malli :errors errors :source source}))])

         ;; not data, no schema to judge by, or malli not loaded: not judged here
         :else nil))))

#?(:clj
   (defn- map-literal-mismatches
     "Why the map literal m at path does not fit rows (`{:key :required :schema :source}`), one
      text per finding led by the key's path (`:order :price`): an unknown key (a literal is
      compiler syntax: a key beyond the rows is a mistake, the map open or closed), a missing
      required key (not judged when a key is not a keyword literal: `{k 1}` may hold any key), then
      each value's own (value-mismatches)."
     [{:keys [path m rows malli cljs?]}]
     (let [row-of (into {} (map (juxt :key identity)) rows)
           at #(path-text (conj path %))]
       (concat
        (for [k (keys m) :when (and (keyword? k) (not (contains? row-of k)))]
          (str (at k) " — unknown key"))
        (when (every? keyword? (keys m))
          (for [{:keys [key required]} rows :when (and required (not (contains? m key)))]
            (str (at key) " — missing required key")))
        (mapcat (fn [[k v]]
                  (when-let [row (row-of k)]
                    (value-mismatches {:path (conj path k) :value v :schema (:schema row) :source (:source row)
                                       :malli malli :cljs? cljs?})))
                m)))))

#?(:clj
   (defn- literal-mismatches
     "Why a map-literal argument does not fit the rows, one text per finding
      (map-literal-mismatches), nested map literals walked against their `[:map …]` rows. A row
      whose schema cannot be evaluated here (clj: the evaluated props; cljs: the source form when
      it is data) skips its value."
     [{:keys [spec arg schema-of malli cljs?]}]
     (map-literal-mismatches {:path [] :m arg :malli malli :cljs? cljs?
                              :rows (for [row (:rows spec)]
                                      {:key (:key row) :required (:required row) :source (:type row)
                                       :schema (try (schema-of row) (catch Exception _ nil))})})))

#?(:clj
   (defn- embeddable?
     "Whether v, written into the compiled call, evaluates to itself: nil, a boolean, number,
      string, keyword or char, or a vector, map or set of those."
     [v]
     (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (char? v)
         (and (or (vector? v) (map? v) (set? v))
              (every? embeddable? (if (map? v) (mapcat identity v) v))))))

#?(:clj
   (defn- literal-fill
     "The value form with the defaults of schema filled in where the source shows the value, as
      fill-value fills it at run time: a map literal under `[:map …]` gets each absent key's
      `:default` and its values filled by their rows, a vector literal or quoted list under
      `[:sequential …]` / `[:vector …]` its items, `[:maybe …]` a non-nil form by its schema; a form
      whose schema holds no default stays as written. ::unfilled when only the run time can fill
      it: a symbol or a call where the schema holds defaults, a default that is not literal data
      (embeddable?), or a map literal of up to 8 entries with a non-literal value that the defaults
      grow past 8 (its values would then evaluate in hash order)."
     [schema form]
     (let [head (when (vector? schema) (first schema))
           items (literal-items form)]
       (cond
         (not (defaults-inside? schema)) form

         (and (= :map head) (map? form))
         (let [filled (reduce (fn [m row]
                                (let [[k _ type] (row-parts row)
                                      type-props (schema-props type)]
                                  (cond
                                    (contains? m k) (let [v (literal-fill type (get m k))]
                                                      (if (= ::unfilled v) (reduced ::unfilled) (assoc m k v)))
                                    (not (contains? type-props :default)) m
                                    (embeddable? (:default type-props)) (assoc m k (:default type-props))
                                    :else (reduced ::unfilled))))
                              form
                              (filter vector? (rest schema)))]
           (if (and (map? filled) (<= (count form) 8) (< 8 (count filled)) (not (constant-form? form)))
             ::unfilled
             filled))

         (and (#{:sequential :vector} head) items)
         (let [filled (mapv #(literal-fill (peek schema) %) items)]
           (cond
             (some #{::unfilled} filled) ::unfilled
             (vector? form) filled
             :else (list 'quote (apply list filled))))

         (= :maybe head) (if (nil? form) form (literal-fill (peek schema) form))

         ;; a symbol, a call or another shape where the schema holds defaults
         :else ::unfilled))))

#?(:clj
   (defn- literal-call
     "`(positional …)` for a map-literal argument whose keys are all rows and that holds every
      required row, an absent row given its default form (nil when optional): the values in place
      when each is a symbol or data (constant-form?), else `(let [g v …] (positional …))`, the
      values bound in the literal's own order (the order the map call would evaluate them in). A
      row read at call time (`:runtime`) takes its value with the defaults inside filled at compile
      time (literal-fill, against `(schema-of row)`); nil when one cannot be (the map call fills
      it)."
     [{:keys [positional rows]} arg schema-of]
     (let [row-of (into {} (map (juxt :key identity)) rows)
           values (into [] (map (fn [[k v]]
                                  (let [row (row-of k)]
                                    [k (if (:runtime row)
                                         (if-let [schema (schema-of row)] (literal-fill schema v) ::unfilled)
                                         v)])))
                        arg)
           locals (into {} (map (fn [k] [k (gensym (str (name k) "__"))])) (keys arg))]
       (cond
         (some #(= ::unfilled (second %)) values) nil
         ;; no value can have effects: the positional call itself, the values in place
         (every? (fn [[_ v]] (or (symbol? v) (constant-form? v))) values)
         (let [value-of (into {} values)]
           `(~positional ~@(map #(get value-of (:key %) (:absent %)) rows)))
         :else
         `(let [~@(mapcat (fn [[k v]] [(locals k) v]) values)]
            (~positional ~@(map #(get locals (:key %) (:absent %)) rows)))))))

#?(:clj
   (defn expand-call
     "A call site of a defn-typed function, as its call-site expander (clj `:inline`, cljs a macro
      of the same name) compiles it: a map-literal argument is checked (literal-mismatches) and a
      mismatch prints one `WARNING defn-typed <file>:<line>: (f …) <findings>` to stderr, or with
      `:literal-check :error` throws an ex-info of that text without `WARNING ` (the compile
      fails), or with `:off` does neither (the function's own `:literal-check`, the spec's, wins
      over the project setting); with the switch on (inline-on?) a literal that fits becomes the
      positional call; everything else = `fallback`, the normal call of the var. The compiled call
      is the same whichever the setting. Throws only that mismatch and a bad setting."
     [{:keys [spec arg fallback cljs? file line]}]
     (let [literal-check (let [project (setting :literal-check)] ; a bad setting fails every call site
                           (or (:literal-check spec) project))]
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
             (when (and mismatches (not= :off literal-check))
               (let [text (str "defn-typed " file ":" line ": (" (name (:name spec)) " …) "
                               (apply str (interpose "; " mismatches)))]
                 (if (= :error literal-check)
                   (throw (ex-info text {::literal-mismatches (vec mismatches)}))
                   (binding [*out* *err*]
                     (println (str "WARNING " text))))))
             (if (and inline? (not mismatches) (:positional spec)
                      ;; a key that is not a keyword literal (unjudged, may be any key): the map call
                      (every? (set (map :key (:rows spec))) (keys arg)))
               (if-let [call (literal-call spec arg schema-of)]
                 (do (when-not cljs?
                       (warn-if-instrumented!)
                       (record-inlined-caller! (:name spec) file))
                     call)
                 fallback)
               fallback)))
         (catch Exception e
           (if (::literal-mismatches (ex-data e)) (throw e) fallback))))))

#?(:clj
   (defn- signature
     "What a literal call compiled to the positional call depends on, as the SHA-256 hex of one
      string (a constant of bounded size in the defining class, whatever the schemas): the spec the
      call-site expander closes over (expand-call) — the positional fn, each row's key and place,
      its type as written (the literal check judges by it; a runtime row's literal-fill fills by
      it), its absent form (the default written into the call), `:required` / `:runtime`, and the
      function's own `:literal-check` — and `:schema-values`, the value of every schema var the
      rows refer to (schema-vars), so a changed `Order` behind `:order Order` changes it. Every
      generated symbol (`p1__12#`, `x__12__auto__`, `G__12`) is written `<stem>__#`, a fn inside a
      value as its class name so written, and a regex prints as its source, so the same source
      gives the same string on every read."
     [spec]
     (let [text (pr-str (clojure.walk/postwalk
                         (fn [x]
                           (let [x (if (fn? x) (symbol (.getName (class x))) x)]
                             (if-let [[_ stem] (and (symbol? x) (re-matches #"(.*?)__\d+(?:__auto__)?#?" (name x)))]
                               (symbol (namespace x) (str stem "__#"))
                               x)))
                         spec))]
       (apply str (map #(format "%02x" %)
                       (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String text "UTF-8")))))))

#?(:clj
   (defonce ^:private signatures
     ;; {<fn> <signature>}: the signature of each defn-typed function's latest definition
     (atom {})))

#?(:clj
   (defn- innermost-message
     "e's message, followed by its innermost cause's when that differs (a compile error's cause
      holds the reason)."
     [^Throwable e]
     (let [root (last (take-while some? (iterate ex-cause e)))]
       (if (= (ex-message e) (ex-message root))
         (str (ex-message e))
         (str (ex-message e) " — " (ex-message root))))))

#?(:clj
   (defn- reload-namespace!
     "Reloads the namespace ns-sym from its file, first dropping it from every inlined-callers entry
      (the reload records the positional calls it compiles). A reload that throws prints
      `defn-typed: reloading <ns-sym> failed: <message>` and returns false; true otherwise."
     [ns-sym]
     (swap! inlined-callers #(reduce-kv (fn [m f callers] (assoc m f (disj callers ns-sym))) {} %))
     (try
       (require ns-sym :reload)
       true
       (catch Throwable e
         (print-err! (str "defn-typed: reloading " ns-sym " failed: " (innermost-message e)))
         false))))

#?(:clj
   (defn- loading?
     "Whether the namespace ns-sym is being loaded right now (`clojure.core/*pending-paths*`: the
      file being loaded and the ones requiring it)."
     [ns-sym]
     (contains? (set @#'clojure.core/*pending-paths*) (#'clojure.core/root-resource ns-sym))))

#?(:clj
   (defn signature!
     "Called by each clj defn-typed definition right after its defn, with its var and signature.
      When an earlier definition of v had another signature, the namespaces holding a literal call
      compiled to its old positional call (inlined-callers) — other than v's own, which is the one
      loading, and one being loaded right now (it compiles against the new definition) — are stale,
      and the `:stale-callers` setting says what happens: `:reload` reloads each from its file
      (reload-namespace!; its literal calls are judged again, so an incompatible one shows its
      warning) and prints `defn-typed: <f> changed its signature, reloaded callers: <ns>, …`;
      `:warn` prints `defn-typed: <f> changed its signature; callers compiled with the old one:
      <ns>, … — reload them`; `:off` does neither. Returns v."
     [v signature]
     (let [f (symbol v)
           previous (get (first (swap-vals! signatures assoc f signature)) f)]
       (when (and previous (not= previous signature))
         (let [callers (->> (disj (get @inlined-callers f) (symbol (namespace f)))
                            (remove loading?)
                            sort)]
           (when (seq callers)
             (case (setting :stale-callers)
               :reload (when-let [reloaded (seq (doall (filter reload-namespace! callers)))]
                         (print-err! (str "defn-typed: " f " changed its signature, reloaded callers: "
                                          (clojure.string/join ", " reloaded))))
               :warn (print-err! (str "defn-typed: " f " changed its signature; callers compiled with the old one: "
                                      (clojure.string/join ", " callers) " — reload them"))
               :off nil))))
       v)))

#?(:clj
   (defn- schema-vars
     "The schema vars the row types of a defn-typed refer to, sorted by name: the vars their
      symbols resolve to where the macro expands, at any depth of the type forms (a props slot
      included), bound to a value that is not a fn (a predicate such as `pos?` holds no rows or
      defaults). The values these hold at the definition are part of its signature."
     [types]
     (->> (tree-seq coll? seq types)
          (filter symbol?)
          (keep #(try (resolve %) (catch Exception _ nil)))
          (filter #(and (var? %) (bound? %) (not (fn? @%))))
          distinct
          (sort-by symbol))))

#?(:clj
   (defonce ^{:doc "`{<fn> #{<schema var> …}}`: the schema vars each defn-typed function watches
      (watch-schemas!), so its next definition removes the watches it no longer needs."}
     schema-watches
     (atom {})))

#?(:clj
   (defn- schema-changed!
     "What a changed schema var (schema-var, now another value) does to f (a qualified symbol)
      typed by it, per the `:stale-callers` setting: nothing while f's namespace is loading (that
      load redefines f with the new value, e.g. a schema of f's own namespace); `:reload` reloads
      f's namespace from its file (reload-namespace!), which redefines f, whose signature! then
      acts on its callers; `:warn` prints `defn-typed: <schema var> changed; <f> and its callers use
      the old one — reload them`; `:off` nothing."
     [f schema-var]
     (let [fn-ns (symbol (namespace f))]
       (when-not (loading? fn-ns)
         (case (setting :stale-callers)
           :reload (reload-namespace! fn-ns)
           :warn (print-err! (str "defn-typed: " (symbol schema-var) " changed; " f
                                  " and its callers use the old one — reload them"))
           :off nil)))))

#?(:clj
   (defn watch-schemas!
     "Called by a clj defn-typed definition after signature!, with its var and the schema vars its
      rows refer to (schema-vars): puts a watch on each, key `[::schema <fn>]`, that calls
      schema-changed! when the var's root changes to another value, and removes the function's
      watches on vars it no longer refers to (a definition replaces its watches, never adds to
      them). No watch at all when `:stale-callers` is `:off`, or when the definition is not read
      from a file (`*file*` NO_SOURCE_PATH: a REPL-typed one has no disk source to reload). A
      watch replaced by a later definition does nothing (a reload of f's namespace triggered by
      one schema var's change replaces the watches that change also notifies). Returns v."
     [v vars]
     (let [f (symbol v)
           watch-key [::schema f]
           watched (if (and *file* (not= "NO_SOURCE_PATH" *file*) (not= :off (setting :stale-callers)))
                     (set vars)
                     #{})
           [before] (swap-vals! schema-watches #(if (seq watched) (assoc % f watched) (dissoc % f)))]
       (doseq [old (get before f) :when (not (contains? watched old))]
         (remove-watch old watch-key))
       (doseq [schema-var watched]
         (add-watch schema-var watch-key
                    (fn watch [_ _ old new]
                      (when (and (not= old new)
                                 (identical? watch (get (.getWatches ^clojure.lang.IRef schema-var) watch-key)))
                        (schema-changed! f schema-var)))))
       v)))

#?(:clj
   (defonce ^{:doc "The defn-typed functions (qualified symbols) whose var held a malli-instrumented fn
      when their definition began (instrumented-before!), until keep-instrumented! takes them."}
     instrumented-definitions
     (atom #{})))

#?(:clj
   (defn instrumented-before!
     "Called by a clj defn-typed definition right before its defn, with its qualified symbol f:
      records f in instrumented-definitions when malli.instrument is loaded and f's var holds a
      fn malli instrumented (its meta has `:malli.instrument/original`), whose wrapper the defn
      is about to drop."
     [f]
     (when (and (find-ns 'malli.instrument)
                (some-> (find-var f) deref meta :malli.instrument/original))
       (swap! instrumented-definitions conj f))
     nil))

#?(:clj
   (defn keep-instrumented!
     "Called by a clj defn-typed definition right after its defn, with its var v: when
      instrumented-before! recorded it, registers v's function schema with malli
      (malli.instrument/-collect!, what collect! does for one var) and instruments v alone
      (malli.instrument/instrument! with default options, so a reporter malli.dev/start! captured
      on malli.core/-fail! still applies; instrumenting unwraps first, so one wrapper). malli is
      resolved, never required: that var being instrumented means it is loaded. Returns v."
     [v]
     (let [f (symbol v)]
       (when (contains? (first (swap-vals! instrumented-definitions disj f)) f)
         ((resolve 'malli.instrument/-collect!) v)
         ((resolve 'malli.instrument/instrument!)
          {:filters [(fn [n s _] (= f (symbol (str n) (str s))))]}))
       v)))

#?(:clj
   (def ^:dynamic *typed-checking*
     "True on the thread that type-checks a definition (typed-check!): the checker expands the
      defn-typed form again, and that expansion queues no check of its own."
     false))

#?(:clj
   (defonce ^:private typed-clojure-present
     ;; whether Typed Clojure's checker is on the classpath, looked up once per JVM without loading it
     (delay (some? (.getResource (clojure.lang.RT/baseLoader) "typed/clj/checker.clj")))))

#?(:clj
   (defonce ^{:private true
              :doc "`{<fn> <form>}`: the defn-typed / defnt form of each clj definition expanded with
      Typed Clojure on the classpath, until its typed-check! takes it."}
     typed-forms
     (atom {})))

#?(:clj
   (def typed-check-ms
     "How long the Typed Clojure check of one definition may run, 2000 ms. It exists for the load:
      the check runs while the namespace loads, and the checker can run for minutes on a large
      body; past it the check is abandoned (its thread left to finish, a daemon) and the load
      goes on."
     2000))

#?(:clj
   (defonce ^:private typed-bridge-loaded
     ;; defn-typed.typed-clojure loaded on the first check; a failed load prints one line and
     ;; switches the checks off for this JVM
     (delay (try (require 'defn-typed.typed-clojure) true
                 (catch Throwable e
                   (print-err! (str "defn-typed: Typed Clojure checks off — loading defn-typed.typed-clojure failed: "
                                    (innermost-message e)))
                   false)))))

#?(:clj
   (defn- typed-findings
     "What checking form (the definition of the var v, from file) gives within typed-check-ms, on
      its own daemon thread (64 MB stack): `[:findings [finding …]]` (check-form!'s, those the
      checker could not type dropped: could-not-type?), `[:skipped \"over 2 s\"]`, `[:skipped
      \"StackOverflowError\"]`, or `[:failed message]` when making the function known threw."
     [{:keys [v form file]}]
     (let [bridge #(deref (resolve (symbol "defn-typed.typed-clojure" %)))
           result (promise)
           check (bound-fn []
                   (deliver result
                            (binding [*typed-checking* true]
                              (try
                                ((bridge "install-fn!") {:fn-var v})
                                [:findings ((bridge "check-form!") {:ns (symbol (namespace (symbol v))) :form form :file file})]
                                (catch Throwable e [:failed (innermost-message e)])))))
           _ (doto (Thread. nil ^Runnable check "defn-typed-typed-check" (* 64 1024 1024))
               (.setDaemon true)
               (.start))
           [kind findings :as outcome] (deref result typed-check-ms [:skipped (str "over " (quot typed-check-ms 1000) " s")])]
       (cond
         (not= :findings kind) outcome
         (some #(re-find #"StackOverflowError" (:message %)) findings) [:skipped "StackOverflowError"]
         :else [:findings (vec (remove #((bridge "could-not-type?") (:message %)) findings))]))))

#?(:clj
   (defn typed-check!
     "Called by a clj defn-typed definition right after its defn when Typed Clojure was on the
      classpath at its expansion, with its var, its defmeta's `:typed-check` (nil: none) and the
      line of its form. Unless the setting (the defmeta's, else the project's) is `:off`: loads
      defn-typed.typed-clojure (once per JVM), makes the function known to it (install-fn!), checks
      the definition form (check-form!) within typed-check-ms and reports each finding, those the
      checker could not type dropped: `:warn` prints `WARNING defn-typed <file>:<line> <message>`
      (the message led by `input of <f>: ` / `output of <f>: ` when it is about a defn-typed
      function), `:error` throws an ex-info of those lines without `WARNING ` (the load fails). A
      check past the deadline prints `defn-typed: Typed Clojure check of <f> skipped (over 2 s)`
      and one whose checker overflowed its stack `… skipped (StackOverflowError)`. Nothing while
      defn-typed.typed-clojure itself loads. Returns v."
     [v mode line]
     (let [f (symbol v)
           form (get (first (swap-vals! typed-forms dissoc f)) f)
           mode (or mode (setting :typed-check))
           file *file*]
       (when (and form (not= :off mode) (not (loading? 'defn-typed.typed-clojure)) @typed-bridge-loaded)
         (let [[kind findings] (typed-findings {:v v :form form :file file})]
           (case kind
             :skipped (print-err! (str "defn-typed: Typed Clojure check of " f " skipped (" findings ")"))
             :failed (print-err! (str "defn-typed: Typed Clojure check of " f " failed: " findings))
             :findings (when (seq findings)
                         (let [texts (for [{:keys [message] :as finding} findings]
                                       (str "defn-typed " (or (:file finding) file) ":" (or (:line finding) line) " " message))]
                           (if (= :error mode)
                             (throw (ex-info (clojure.string/join "\n" texts) {::typed-findings (vec findings)}))
                             (doseq [text texts] (print-err! (str "WARNING " text)))))))))
       v)))

#?(:clj
   (defn- install-cljs-expander!
     "Makes `name` a macro for the cljs analyzer, beside the fn of that name: interned in the clj
      namespace of the same name (where the analyzer looks up `alias/name` and `ns/name`) and
      recorded as the ns's own `:use-macros` (where it looks up a bare `name` in that ns) and among
      its analyzed `:macros` (so a namespace that `:refer`s name gets it into its `:use-macros`,
      shadow-cljs infer-macro-use). Value position keeps resolving the fn. Called in a release (`:advanced`) build only: a cached
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
                  update-in [:cljs.analyzer/namespaces ns-sym]
                  #(-> %
                       (assoc-in [:use-macros fn-name] ns-sym)
                       ;; the ns's analyzed macros: a namespace compiled later that `:refer`s name
                       ;; gets it as a macro too (shadow-cljs infer-macro-use reads this map)
                       (assoc-in [:macros fn-name] {:name (symbol (str ns-sym) (str fn-name)) :ns ns-sym :macro true}))))))))

#?(:clj
   (defn- plumbing
     "form marked `^:typed.clojure/ignore`: Typed Clojure skips it and types it t/Any. Every
      generated form that holds no user code carries it (the body lives in <name>--positional), so
      a check sees the user's body and calls, never the expansion's wiring."
     [form]
     (vary-meta form assoc :typed.clojure/ignore true)))

#?(:clj
   (defonce ^{:private true
              :doc "`{<fn> <load scope>}`: the defn-typed functions expanded with no defmeta above them,
      each with the load-scope it expanded in, until a defmeta of that name expands: one in the
      same scope is written below the definition."}
     awaiting-meta
     (atom {})))

#?(:clj
   (defn- load-scope
     "What identifies the file being loaded or compiled where a macro expands: the thread binding of
      clj `*file*` (Compiler.load binds it once per file) or cljs `cljs.analyzer/*cljs-file*` (bound
      once per file compile), identical for every form of one load and new on the next; nil outside
      a file (a form typed into a REPL)."
     [cljs?]
     (some-> ^clojure.lang.Var (if cljs? (resolve 'cljs.analyzer/*cljs-file*) #'*file*) .getThreadBinding)))

#?(:clj
   (defn- dev-only
     "form as it runs in a dev build: cljs under goog.DEBUG (a release build drops it), clj as is."
     [cljs? form]
     (if cljs? `(when ~(with-meta 'goog.DEBUG {:tag 'boolean}) ~form) form)))

#?(:clj
   (defn- pending-vars
     "cljs: the vars of the namespace being compiled that are declared but not defined yet (their
      analyzed def is `:declared`), as `(var …)` forms; clj: none."
     [env]
     (when (:ns env)
       (let [ns-sym (-> env :ns :name)
             defs (get-in @@(resolve 'cljs.env/*compiler*) [:cljs.analyzer/namespaces ns-sym :defs])]
         (vec (for [[sym {:keys [declared]}] (sort-by key defs) :when declared]
                `(var ~(symbol (str ns-sym) (str sym)))))))))

#?(:clj
   (defn- inout-check-call
     "The `(inout-check! {…})` form that runs the cases of fn-name after its definition, under
      goog.DEBUG in cljs, when m (its defmeta's map, the defmeta's line as its `::line` meta) holds
      `:inout-tests` and its `:inout-check` (m's, else the project's) is not `:off`; nil otherwise."
     [{:keys [env fn-name m]}]
     (let [mode (or (:inout-check m) (setting :inout-check))
           cljs? (boolean (:ns env))]
       (when (and (contains? m :inout-tests) (not= :off mode))
         (dev-only cljs?
                   `(inout-check! {:var (var ~fn-name) :mode ~mode :line ~(::line (meta m))
                                   :file ~(if cljs? (some-> (resolve 'cljs.analyzer/*cljs-file*) deref str) *file*)
                                   :pending ~(pending-vars env)}))))))

#?(:clj
   (defmacro defn-typed
     "(defn-typed name {key schema …} -> <out-schema> body…): a one-arity function of one map.
      The input = the map literal of its rows, one entry per line: `key schema`, or `key [props schema]`
      for a row with props; metadata on the map is a compile error. Wrapped into `[:map …]` and
      def'd as `<name>-props`.
      The body sees every row key as a local of the same name (`:line-h` → `line-h`), defaults
      filled. Expands to `(def <name>-props [:map …])`,
      `(defn <name>--positional [k…] body…)` (the rows in entry order) and
      `(defn name {:malli/schema [:=> [:cat <name>-props] <out-schema>] :arglists '([{:keys [k…]}])} [m]
      (let [{:keys [k…] :or {k default}} m] (<name>--positional k…)))`, so everything that reads defn
      and :malli/schema sees a plain defn, and doc shows the rows as its arglist; clj then calls
      `(signature! #'name \"<signature>\")`, which reloads the callers compiled against another one,
      around the defn `(instrumented-before! 'name)` / `(keep-instrumented! #'name)`, which instrument
      the new fn when malli had instrumented the one it replaces,
      and `(watch-schemas! #'name [#'Schema …])` when a row refers to a schema var, whose change
      reloads this namespace.
      A default = `:default` in the row type's own props (`:qty [:int {:default 1}]`), read at
      compile time; a row whose defaults only the evaluated schema shows (runtime-type?) is bound
      through row-value. Instrumentation checks the call
      before the defaults are filled, so the macro marks a defaulted row `{:optional true}`
      (defaults-optional). name, called with a real map, reports its keys beyond the rows
      (report-unknown-keys!) as the `:unknown-keys` setting says (the defmeta's, else the project's);
      `:off` emits no such code. Every call site goes through expand-call (clj `:inline`, cljs a macro of
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
        (let [_ @project-settings ; a bad defn-typed.edn fails the first definition
              table (table-rows fail! input)
              _ (when-let [paths (seq (entry-default-paths table))]
                  (fail! (str (apply str (interpose ", " (map path-text paths))) " · " entry-default-rule)))
              in-schema (defaults-optional table)
              ;; a row whose type holds a symbol or a call shows its default only when evaluated
              props-form (into [] (map #(if (and (vector? %) (not (constant-form? (peek %))))
                                          `(optional-if-defaulted ~%)
                                          %))
                               in-schema)
              props (symbol (str fn-name "-props"))
              positional (symbol (str fn-name "--positional"))
              cljs? (boolean (:ns &env))
              q (qualified &env fn-name)
              q-props (qualified &env props)
              rows (vec (map-indexed
                         (fn [i row]
                           (let [[k row-props type] (row-parts row)
                                 runtime (runtime-type? &env type)
                                 plan {:key k :local (symbol (name k)) :binding (symbol (namespace k) (name k))
                                       :type type :props q-props :index (inc i)}]
                             (cond-> (assoc plan :runtime runtime)
                               (default-slot? type) (assoc :absent (default-form plan))
                               (and (not (default-slot? type)) (:optional row-props)) (assoc :absent nil)
                               ;; a row read at call time whose type is a symbol or a call: its
                               ;; default, if any, shows only in the evaluated schema
                               (and runtime (not (default-slot? type)) (or (symbol? type) (seq? type)))
                               (assoc :absent `(:default (schema-props (peek (nth ~q-props ~(inc i))))))
                               ;; a type written as a symbol or a call may carry a default: not judged
                               (not (or (default-slot? type) (:optional row-props) (symbol? type) (seq? type)))
                               (assoc :required true))))
                         (filter vector? (rest in-schema))))
              locals (mapv :local rows)
              _ (when-let [[local keys] (first (filter #(next (val %)) (group-by :local rows)))]
                  (fail! (str (apply str (interpose " and " (map (comp pr-str :key) keys))) " both bind " local)))
              ;; clojure fns take at most 20 positional params: a larger table keeps its body in name;
              ;; so does a body whose recur targets the function (it recurs with the map)
              positional? (and (<= (count locals) 20) (not-any? #(self-recur? (expanded &env %)) body))
              m (gensym "m")
              bindings (into [(let [static (remove :runtime rows)
                                    ;; an optional row without a default reads nil either way
                                    defaults (into {} (keep #(when (some? (:absent %)) [(:local %) (:absent %)]))
                                                   static)]
                                (cond-> {:keys (mapv :binding static)} (seq defaults) (assoc :or defaults)))
                              m]
                             (mapcat #(vector (:local %) `(row-value (nth ~props ~(:index %)) ~m))
                                     (filter :runtime rows)))
              ;; the defmeta written above: its keys other than the cases go into the defn's attr-map,
              ;; so :doc is the var's docstring in clj and cljs alike
              defmeta-above (get @pending-meta q)
              meta-keys (dissoc defmeta-above :inout-tests)
              ;; a real map's keys beyond the rows, reported by name's :unknown-keys; no code for :off
              unknown-keys (let [project (setting :unknown-keys)] (or (:unknown-keys meta-keys) project))
              ;; m holds a key beyond the rows iff it holds more keys than rows it holds: a count and
              ;; one contains? per row on the call path, the keys themselves listed only then; clj
              ;; calls the map's own methods (RT/contains doubles the cost of the whole map call)
              key-check (when (not= :off unknown-keys)
                          (let [row-keys (mapv :key rows)
                                hinted (with-meta m {:tag (if cljs? 'cljs.core/IMap 'clojure.lang.IPersistentMap)})
                                has? (fn [k] (if cljs? `(contains? ~m ~k) `(.containsKey ~hinted ~k)))]
                            (plumbing
                             `(when (and (map? ~m)
                                         (not (== ~(if cljs? `(count ~m) `(.count ~hinted))
                                                  (+ ~@(map (fn [k] `(if ~(has? k) 1 0)) row-keys)))))
                                (report-unknown-keys! {:fn '~q :mode ~unknown-keys :keys ~row-keys
                                                       :unknown-keys (into [] (remove ~(set row-keys)) (keys ~m))})))))
              malli-opts (let [v (:malli-in-prod meta-keys)] (when (and v (not= false v)) v))
              spec (cond-> {:name q :props q-props
                            :rows (mapv #(select-keys % [:key :index :type :absent :required :runtime]) rows)}
                     ;; the function's own :literal-check wins over the project's at its call sites
                     (:literal-check meta-keys) (assoc :literal-check (:literal-check meta-keys))
                     ;; a :malli-in-prod call must pass the check in name: never the positional call
                     (and positional? (not malli-opts))
                     (assoc :positional (qualified &env positional)))
              ;; the map's rows as a destructuring arglist, so doc and editors show the inputs, not m
              arglists (list 'quote (list [{:keys (mapv :binding rows)}]))
              attrs (cond-> (merge {:arglists arglists} meta-keys {:malli/schema [:=> [:cat props] out-schema]})
                      (not cljs?) (assoc :inline-arities #{1}
                                         ;; the fallback is a host call on the var's value: no op
                                         ;; position, so it is never expanded again
                                         :inline (plumbing
                                                  `(fn [arg#]
                                                     (expand-call {:spec '~spec :arg arg# :cljs? false
                                                                   :fallback (list '.invoke (with-meta '~q {:tag 'clojure.lang.IFn}) arg#)
                                                                   :file *file* :line (deref clojure.lang.Compiler/LINE)})))))]
          (swap! pending-meta dissoc q)
          ;; no cases above: a defmeta below, in this same load, runs them (defmeta)
          (when-not *typed-checking*
            (if (contains? defmeta-above :inout-tests)
              (swap! awaiting-meta dissoc q)
              (swap! awaiting-meta assoc q (load-scope cljs?))))
          ;; cljs: a release build only (see install-cljs-expander!); a dev build keeps plain calls
          (when (and cljs? (inline-on? true)) (install-cljs-expander! &env fn-name spec))
          (let [call (if positional? `(let ~bindings (~positional ~@locals)) `(let ~bindings ~@body))
                body-defn (when positional? `(defn ~positional {:no-doc true} ~locals ~@body))
                ;; clj: after the defn, callers compiled against another signature are stale (cljs:
                ;; shadow-cljs recompiles the namespaces that depend on a changed one)
                ;; clj: the schema vars the rows refer to — their values are part of the signature,
                ;; and a change of one reloads this namespace (watch-schemas!)
                deps (when-not cljs? (schema-vars (map :type rows)))
                ;; clj with Typed Clojure on the classpath: the definition is type-checked once
                ;; evaluated (typed-check!), unless its defmeta says :off
                typed-check-call (when (and (not cljs?) (not *typed-checking*) @typed-clojure-present
                                            (not= :off (:typed-check meta-keys)))
                                   (swap! typed-forms assoc q &form)
                                   [(plumbing `(typed-check! (var ~fn-name) ~(:typed-check meta-keys) ~(:line (meta &form))))])
                ;; clj: a var malli instrumented stays instrumented through the redefinition (cljs: malli
                ;; collects schemas at compile time, a macro, and re-instruments through
                ;; malli.dev.cljs/start! in the hot-reload after-load hook)
                instrumented-before-call (when-not cljs? [(plumbing `(instrumented-before! '~q))])
                keep-instrumented-call (when-not cljs? [(plumbing `(keep-instrumented! (var ~fn-name)))])
                ;; the defmeta's cases run once the definition (re-instrumented) is in place
                inout-call (when-not *typed-checking*
                             (some-> (inout-check-call {:env &env :fn-name fn-name :m defmeta-above})
                                     plumbing vector))
                signature-call (when-not cljs?
                                 (cond-> [(plumbing `(signature! (var ~fn-name)
                                                                 ~(signature (cond-> spec (seq deps)
                                                                               (assoc :schema-values (into (sorted-map) (map (juxt symbol deref)) deps))))))]
                                   ;; a function that watched schema vars and refers to none now removes its watches
                                   (or (seq deps) (contains? @schema-watches q))
                                   (conj (plumbing `(watch-schemas! (var ~fn-name) [~@(map (fn [v] `(var ~(symbol v))) deps)])))))]
            (if-not malli-opts
              `(do
                 ~(plumbing `(def ~props ~props-form))
                 ;; the body calls name before its defn: a recursive call, name passed as a value
                 ~@(when positional? [`(declare ~fn-name) body-defn])
                 ~@instrumented-before-call
                 ;; positional: name only binds the rows and calls the body; else the body is here
                 ~(cond-> `(defn ~fn-name ~attrs [~m]
                             ~@(when key-check [key-check])
                             ~call)
                    positional? plumbing)
                 ~@keep-instrumented-call
                 ~@inout-call
                 ~@signature-call
                 ~@typed-check-call)
              ;; :malli-in-prod: name checks the map and the result around the body, which lives in
              ;; <name>--positional or <name>--body (a recur there recurs with the map, unchecked)
              (let [slot (symbol (str fn-name "--malli-in-prod"))
                    body-fn (symbol (str fn-name "--body"))
                    checker (gensym "checker")
                    check? (gensym "check")
                    result (gensym "result")]
                `(do
                   ~(plumbing `(def ~props ~props-form))
                   ~(plumbing `(def ~(with-meta slot {:no-doc true})
                                 (malli-in-prod-slot {:fn '~q :input ~props :output ~out-schema :opts '~malli-opts})))
                   (declare ~fn-name)
                   ~(or body-defn `(defn ~body-fn {:no-doc true} [~m] ~call))
                   ~@instrumented-before-call
                   ~(plumbing
                     `(defn ~fn-name ~attrs [~m]
                        ~@(when key-check [key-check])
                        (let [~checker (malli-checker ~slot)
                              ~check? (malli-check? ~checker)]
                          (when ~check? (malli-check! ~checker :input ~m))
                          (let [~result ~(if positional? call `(~body-fn ~m))]
                            (when ~check? (malli-check! ~checker :output ~result))
                            ~result))))
                   ~@keep-instrumented-call
                   ~@inout-call
                   ~@signature-call
                   ~@typed-check-call)))))))))

#?(:clj
   (defmacro defnt
     "defn-typed under a short name: (defnt name {key schema …} -> <out-schema> body…) is the
      defn-typed macro called with the same form, so it expands to exactly what defn-typed does."
     {:arglists (:arglists (meta #'defn-typed))}
     [& args]
     (apply @#'defn-typed &form &env args)))

#?(:clj
   (defmacro defmeta
     "(defmeta name {…}): everything about the function `name` other than its signature, written
      right ABOVE its defn-typed/defn, one empty line apart. `:inout-tests` = [in expected] pairs, in
      = the function's one argument (single-arg-pairs), registered at load as `tests` does; every
      other key (`:doc` …) goes into the attr-map of the defn-typed below (pending-meta), so it is
      var metadata in clj and cljs. Expands to (declare name) + the registration, so it runs before
      the var is defined; the keys other than the cases are also recorded in `registry` as the var's
      `:meta` (register-meta!), which is where a plain defn below keeps them. In cljs all of it is
      under goog.DEBUG, like the cases. `:literal-check`, `:unknown-keys` and `:typed-check` (`:warn`,
      `:error` or `:off`) are the function's own settings, winning over the project's; a bad value
      fails here."
     [fn-name m]
     (let [fail! #(throw (ex-info (str "defmeta " fn-name ": " %) {:fn fn-name}))
           cljs? (boolean (:ns &env))
           dev #(dev-only cljs? %)]
       (when-not (symbol? fn-name)
         (fail! "the first argument must be the function's name"))
       (when-not (map? m)
         (fail! (str "the metadata must be a map literal, got " (pr-str m))))
       (when-let [problem (and (contains? m :malli-in-prod) (malli-in-prod-opts-problem (:malli-in-prod m)))]
         (fail! (str ":malli-in-prod is " problem)))
       (doseq [k [:literal-check :unknown-keys :typed-check :inout-check]
               :let [problem (and (contains? m k) (setting-value-problem k (get m k)))]
               :when problem]
         (fail! problem))
       (let [other (dissoc m :inout-tests)
             q (qualified &env fn-name)
             ;; a defn-typed of this name expanded earlier in this same load, with no cases above it
             [awaiting] (swap-vals! awaiting-meta dissoc q)
             below? (and (contains? awaiting q) (identical? (get awaiting q) (load-scope cljs?)))
             m (vary-meta m assoc ::line (:line (meta &form)))]
         ;; below the definition its cases are run here, so they stay out of the next definition's
         (swap! pending-meta assoc q (cond-> m below? (dissoc :inout-tests)))
         `(do
            (declare ~fn-name)
            ~@(when (seq other)
                [(plumbing (dev `(register-meta! (var ~fn-name) ~other)))])
            ~@(when (contains? m :inout-tests)
                [(plumbing (dev `(register-tests! (var ~fn-name) (single-arg-pairs '~q ~(:inout-tests m)))))])
            ~@(when below?
                (some-> (inout-check-call {:env &env :fn-name fn-name :m m})
                        plumbing vector)))))))

(declare with-defaults)

(defn- fill-value
  "value with the defaults schema holds filled at any depth: a map under `[:map …]` (with-defaults),
   each item of a sequential value under `[:sequential …]` / `[:vector …]` whose item schema holds
   defaults (defaults-inside?; a vector stays a vector), the value under `[:maybe …]` by its schema;
   anything else, nil included, is value itself."
  [schema value]
  (case (when (vector? schema) (first schema))
    :map (if (map? value) (with-defaults schema value) value)
    :maybe (fill-value (peek schema) value)
    (:sequential :vector) (let [items (peek schema)]
                            (cond
                              (not (and (sequential? value) (defaults-inside? items))) value
                              (vector? value) (mapv #(fill-value items %) value)
                              :else (map #(fill-value items %) value)))
    ;; any other schema: with-defaults leaves its value as it is
    value))

(defn- fill-row
  "m with row's key filled as with-defaults fills it: absent → the `:default` of the row type's own
   props (when it has one), present → its value filled by the row type (fill-value)."
  [m row]
  (let [[k _ type] (row-parts row)
        type-props (schema-props type)]
    (if (contains? m k)
      (let [value (get m k)
            filled (fill-value type value)]
        (if (identical? value filled) m (assoc m k filled)))
      (cond-> m (contains? type-props :default) (assoc k (:default type-props))))))

(defn with-defaults
  "props with the default of every `[:map …]` row whose key props lacks, the `:default` of the
   row type's own props (`[:a [:int {:default 1}]]`); a present key, explicit nil included, is
   kept. Row = [k type] or [k row-props type]; a present value is filled by its row's type at any
   depth: a map under `[:map …]`, the items of `[:sequential …]` / `[:vector …]`, a value under
   `[:maybe …]`. nil props = {}."
  [schema props]
  (reduce fill-row (or props {}) (filter vector? (rest schema))))

(defn row-value
  "The value with-defaults gives row's key of m: what a defn-typed binds for a row whose defaults
   only the evaluated schema shows (a symbol or a call as its type, defaults inside its type)."
  [row m]
  (get (fill-row m row) (first row)))

;; qualified: cljs resolves a macro of the ns being compiled only through its ns name
(defn-typed.core/tests #'with-defaults
  [[[[:map [:a {:optional true} [:int {:default 1}]]] {}]                          {:a 1}]
   [[[:map [:a [:int {:min 0 :default 1}]]] {:a 2}]                              {:a 2}]
   [[[:map [:a [:maybe {:default 1} :int]]] {:a nil}]                            {:a nil}]
   [[[:map [:n [:map [:b [:string {:default "x"}]]]]] {:n {}}]                   {:n {:b "x"}}]
   [[[:map [:a {:default 1} :int]] {}]                                           {}]
   [[[:map {:closed true} [:a :int] [:b {:optional true} :int]] nil]             {}]
   [[[:map [:s [:sequential [:map [:q [:int {:default 1}]]]]]] {:s [{} {:q 2}]}]  {:s [{:q 1} {:q 2}]}]
   [[[:map [:v [:vector [:maybe [:map [:q [:int {:default 1}]]]]]]] {:v [nil {}]}] {:v [nil {:q 1}]}]
   [[[:map [:s [:sequential [:map [:q [:int {:default 1}]]]]]] {:s nil}]          {:s nil}]])

(defn-typed.core/tests #'schema-props
  [[[[:int {:min 1 :default 1}]] {:min 1 :default 1}]
   [[:int]                        nil]
   [[[:maybe :int]]               nil]])

(defn-typed.core/tests #'entry-default-paths
  [[[[:map [:a {:default 1} :int] [:b [:int {:default 2}]]]]  [[:a]]]
   [[[:map [:n [:map [:c {:default "x"} :string]]]]]         [[:n :c]]]
   [[[:map [:s [:sequential [:map [:c {:default 1} :int]]]]]] [[:s :c]]]
   [[:int]                                                   []]])

(defn-typed.core/tests #'defaults-optional
  [[[[:map {:closed true} [:a :int] [:b [:int {:default 2}]]]]
    [:map {:closed true} [:a :int] [:b {:optional true} [:int {:default 2}]]]]
   [[[:map [:c {:optional true} [:maybe :int]]]]
    [:map [:c {:optional true} [:maybe :int]]]]
   [[[:map [:n [:map [:b [:string {:default "x"}]]]]]]
    [:map [:n [:map [:b {:optional true} [:string {:default "x"}]]]]]]
   [[[:map [:s [:vector [:maybe [:map [:q [:int {:default 1}]]]]]]]]
    [:map [:s [:vector [:maybe [:map [:q {:optional true} [:int {:default 1}]]]]]]]]
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

(defn- run-case
  "One case [i args expected] of the var v as check-var runs it: {:i :in :expected :actual}; a
   throwing case reports `:actual [:thrown message]`, its ex-data as `:thrown-data` and the
   throwable itself as `::thrown`."
  [v [i args expected]]
  (when *trace-cases*
    (println (str "inout-case " (var-name v) " " i " " (pr-str args)))
    (flush))
  (try {:i i :in args :expected expected :actual (apply @v args)}
       (catch #?(:clj Throwable :cljs :default) e
         {:i i :in args :expected expected
          :actual [:thrown (ex-message e)] :thrown-data (ex-data e) ::thrown e})))

(defn- failed? [{:keys [expected actual]}]
  (not= expected actual))

(defn check-var
  "{:var sym :cases n :failures [{:i :in :expected :actual}]}; a throwing case
   reports `:actual [:thrown message]` and its ex-data as `:thrown-data`."
  [v]
  (let [results (mapv #(run-case v %) (var-cases v))]
    {:var (var-name v)
     :cases (count results)
     :failures (into [] (comp (filter failed?) (map #(dissoc % ::thrown))) results)}))

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

(defn- case-text
  "`<file>:<line> <ns/f> in/out case <i>: <in> → expected <out>, got <actual>` for a failing case
   (run-case's result); a throwing one ends `threw <message>`."
  [{:keys [file line f result]}]
  (let [{:keys [i in expected actual]} result]
    (str file ":" line " " f " in/out case " i ": " (pr-str (shown-in in)) " → expected " (pr-str expected) ", "
         (if (contains? result ::thrown) (str "threw " (second actual)) (str "got " (pr-str actual))))))

(defn- report-cases!
  "Reports texts (case-text) as mode says: `:warn` one `WARNING <text>` stderr line each (cljs
   console.error), `:error` throws an ex-info of the texts, one per line."
  [mode texts]
  (when (seq texts)
    (if (= :error mode)
      (throw (ex-info (apply str (interpose "\n" texts)) {::inout-failures (vec texts)}))
      (doseq [text texts] (print-err! (str "WARNING " text))))))

#?(:clj
   (defn- unbound-call?
     "Whether e (or a cause of it) is the throw of calling a var that holds no value yet: a
      `(declare g)` whose defn comes further down the file (thrown by clojure.lang.Var$Unbound)."
     [^Throwable e]
     (boolean (some (fn [^Throwable t]
                      (and (instance? IllegalStateException t)
                           (= "clojure.lang.Var$Unbound" (some-> (first (.getStackTrace t)) .getClassName))))
                    (take-while some? (iterate ex-cause e))))))

#?(:clj
   (defn- unbound-vars
     "The vars interned in ns-sym that hold no value yet, taken from their Var$Unbound roots."
     [ns-sym]
     (keep (fn [^clojure.lang.Var v]
             (let [root (.getRawRoot v)]
               (when (instance? clojure.lang.Var$Unbound root) (.-v ^clojure.lang.Var$Unbound root))))
           (vals (ns-interns ns-sym)))))

#?(:clj
   (defonce ^{:doc "`{<fn> #{[<var> <watch key>] …}}`: the one-shot watches inout-check! put on the
      vars a case of <fn> called before they were defined; the next definition of <fn> removes them."}
     deferred-cases
     (atom {})))

#?(:clj
   (defn- forget-deferred!
     "Removes the watches of f's (a qualified symbol) deferred cases, all of them or those of watch-key."
     ([f] (forget-deferred! f nil))
     ([f watch-key]
      (let [mine? #(or (nil? watch-key) (= watch-key (second %)))
            [before] (swap-vals! deferred-cases update f #(set (remove mine? %)))]
        (doseq [[v k] (filter mine? (get before f))]
          (remove-watch v k))))))

(declare inout-run!)

#?(:clj
   (defn- defer-case!
     "Puts a one-shot watch, key `[::deferred-case <f> <i>]`, on each of vars (the unbound vars of
      f's namespace): the first that becomes bound removes them all and runs case i again
      (inout-run! with `:only i`)."
     [{:keys [check i vars]}]
     (let [f (symbol (:var check))
           watch-key [::deferred-case f i]]
       (swap! deferred-cases update f (fnil into #{}) (map (fn [v] [v watch-key]) vars))
       (doseq [v vars]
         (add-watch v watch-key
                    (fn [_ _ _ value]
                      (when-not (instance? clojure.lang.Var$Unbound value)
                        (forget-deferred! f watch-key)
                        (inout-run! (assoc check :only i)))))))))

(defn- inout-run!
  "Runs the cases of check's var (all, or `:only` the one of that index) as check-var runs them and
   reports the failing ones (report-cases! with `:mode`, at `:file` `:line`, the defmeta's). A case that threw because it called a function not defined yet is not reported:
   clj defers it (defer-case!) until one of its namespace's unbound vars is defined; cljs skips it
   (it threw while one of `:pending`, the vars declared above but not yet defined, is undefined)."
  [{:keys [var mode file line only] :as check}]
  (let [results (for [[i :as c] (var-cases var) :when (or (nil? only) (= i only))] (run-case var c))
        waiting? (fn [{e ::thrown}]
                   (and e #?(:clj (unbound-call? e)
                             :cljs (boolean (some #(undefined? @%) (:pending check))))))
        failures (filter failed? results)]
    #?(:clj (doseq [{:keys [i]} (filter waiting? failures)
                    :let [vars (unbound-vars (symbol (namespace (symbol var))))]
                    :when (seq vars)]
              (defer-case! {:check check :i i :vars vars})))
    (report-cases! mode (for [result (remove waiting? failures)]
                          (case-text {:file file :line line
                                      :f (var-name var) :result result})))))

(defn inout-check!
  "Called right after a defn-typed definition whose defmeta above holds `:inout-tests` (after its
   keep-instrumented!), or by a defmeta written below the definition, with
   {:var :mode :file :line :pending}: runs the var's cases, the ones check-var runs, and
   reports each failing one as mode (`:inout-check`, never `:off`: no call is emitted then) says:
   `:warn` prints `WARNING <file>:<line> <ns/f> in/out case <i>: <in> → expected <out>, got
   <actual>` (`threw <message>`), `:error` throws that text. All passing: nothing. A case calling a
   function defined further down waits for it (inout-run!). Replaces the waiting cases of the
   function's previous definition. Returns the var."
  [{:keys [var] :as check}]
  #?(:clj (forget-deferred! (symbol var)))
  (inout-run! check)
  var)

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
