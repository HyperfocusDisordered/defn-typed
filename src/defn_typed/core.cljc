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
  #?(:clj (:require [clojure.edn]
                   [clojure.test :as test])
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
     "The settings of defn-typed.edn and their allowed values, the first one the default."
     {:literal-check [:warn :error]
      :inline [true false]}))

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
      `{:literal-check :warn :inline true}`, merged with the file's map. Throws, naming the file,
      when its content is not a map, holds a key other than those two, or a value outside the key's
      allowed ones."
     [^java.io.File file]
     (let [where (str "defn-typed " (some-> file .getPath) ": ")
           m (if file (clojure.edn/read-string (slurp file)) {})]
       (when-not (map? m)
         (throw (ex-info (str where "the settings must be a map, got " (pr-str m)) {:file file})))
       (doseq [[k v] m]
         (let [allowed (get setting-values k)]
           (cond
             (nil? allowed)
             (throw (ex-info (str where "unknown key " (pr-str k) " — the keys are "
                                  (apply str (interpose ", " (keys setting-values))))
                             {:file file :key k}))
             (not-any? #(= v %) allowed)
             (throw (ex-info (str where (pr-str k) " must be one of "
                                  (apply str (interpose ", " (map pr-str allowed))) ", got " (pr-str v))
                             {:file file :key k}))
             ;; a known key with an allowed value: merged below
             :else nil)))
       (merge (into {} (map (fn [[k vs]] [k (first vs)])) setting-values) m))))

#?(:clj
   (defonce ^:private project-settings
     ;; the defn-typed.edn serving the JVM's working directory (where clj and shadow-cljs compile):
     ;; read once per JVM, at the first macroexpansion
     (delay (settings-of-file (settings-file (java.io.File. (System/getProperty "user.dir")))))))

#?(:clj
   (defn- setting
     "A setting's value: the JVM system property `defn-typed.<key>` when set (`defn-typed.inline`:
      anything but `false` is on; `defn-typed.literal-check`: `warn` or `error`), else the
      project's defn-typed.edn, else the default."
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
     "`(let [g v …] (positional …))` for a map-literal argument whose keys are all rows and that
      holds every required row: the values bound in the literal's own order (the order the map
      call would evaluate them in), an absent row given its default form (nil when optional). A
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
       (when-not (some #(= ::unfilled (second %)) values)
         `(let [~@(mapcat (fn [[k v]] [(locals k) v]) values)]
            (~positional ~@(map #(get locals (:key %) (:absent %)) rows)))))))

#?(:clj
   (defn expand-call
     "A call site of a defn-typed function, as its call-site expander (clj `:inline`, cljs a macro
      of the same name) compiles it: a map-literal argument is checked (literal-mismatches) and a
      mismatch prints one `WARNING defn-typed <file>:<line>: (f …) <findings>` to stderr, or with
      the setting `:literal-check :error` throws an ex-info of that text without `WARNING ` (the
      compile fails); with the switch on (inline-on?) a literal that fits becomes the positional
      call; everything else = `fallback`, the normal call of the var. Throws only that mismatch
      and a bad setting."
     [{:keys [spec arg fallback cljs? file line]}]
     (let [literal-check (setting :literal-check)] ; a bad setting fails every call site
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
                 (do (when-not cljs? (warn-if-instrumented!))
                     call)
                 fallback)
               fallback)))
         (catch Exception e
           (if (::literal-mismatches (ex-data e)) (throw e) fallback))))))

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
      and :malli/schema sees a plain defn, and doc shows the rows as its arglist.
      A default = `:default` in the row type's own props (`:qty [:int {:default 1}]`), read at
      compile time; a row whose defaults only the evaluated schema shows (runtime-type?) is bound
      through row-value. Instrumentation checks the call
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
              meta-keys (dissoc (get @pending-meta q) :inout-tests)
              malli-opts (let [v (:malli-in-prod meta-keys)] (when (and v (not= false v)) v))
              spec (cond-> {:name q :props q-props
                            :rows (mapv #(select-keys % [:key :index :type :absent :required :runtime]) rows)}
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
          ;; cljs: a release build only (see install-cljs-expander!); a dev build keeps plain calls
          (when (and cljs? (inline-on? true)) (install-cljs-expander! &env fn-name spec))
          (let [call (if positional? `(let ~bindings (~positional ~@locals)) `(let ~bindings ~@body))
                body-defn (when positional? `(defn ~positional {:no-doc true} ~locals ~@body))]
            (if-not malli-opts
              `(do
                 ~(plumbing `(def ~props ~props-form))
                 ;; the body calls name before its defn: a recursive call, name passed as a value
                 ~@(when positional? [`(declare ~fn-name) body-defn])
                 ;; positional: name only binds the rows and calls the body; else the body is here
                 ~(cond-> `(defn ~fn-name ~attrs [~m]
                             ~call)
                    positional? plumbing))
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
                   ~(plumbing
                     `(defn ~fn-name ~attrs [~m]
                        (let [~checker (malli-checker ~slot)
                              ~check? (malli-check? ~checker)]
                          (when ~check? (malli-check! ~checker :input ~m))
                          (let [~result ~(if positional? call `(~body-fn ~m))]
                            (when ~check? (malli-check! ~checker :output ~result))
                            ~result)))))))))))))

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
                [(plumbing (dev `(register-meta! (var ~fn-name) ~other)))])
            ~@(when (contains? m :inout-tests)
                [(plumbing (dev `(register-tests! (var ~fn-name) (single-arg-pairs '~q ~(:inout-tests m)))))]))))))

(declare with-defaults)

(defn- fill-value
  "value with the defaults schema holds filled at any depth: a map under `[:map …]` (with-defaults),
   each item of a sequential value under `[:sequential …]` / `[:vector …]` whose item schema holds
   defaults (defaults-inside?; a vector stays a vector), the value under `[:maybe …]` by its schema;
   anything else, nil included, is value itself."
  [schema value]
  (let [head (when (vector? schema) (first schema))
        items (items-schema schema)]
    (cond
      (= :map head) (if (map? value) (with-defaults schema value) value)

      (= :maybe head) (fill-value items value)

      (and items (sequential? value) (defaults-inside? items))
      (if (vector? value) (mapv #(fill-value items %) value) (map #(fill-value items %) value))

      ;; no defaults inside, or a value of another shape: as it is
      :else value)))

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
