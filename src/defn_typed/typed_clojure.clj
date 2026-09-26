(ns defn-typed.typed-clojure
  "Typed Clojure checks of defn-typed code, with the types the signatures already declare: no
   hand-written annotation per function. Optional: the library does not depend on Typed Clojure;
   require this namespace only with `org.typedclojure/typed.clj.checker` and
   `org.typedclojure/typed.malli` on the classpath.

   - `install!` makes the checker know every defn-typed function of the given namespaces: the
     function itself through typed.malli's var-type provider (which reads malli's function
     schemas, so it collects them), `<name>--positional` (where the body lives) and `<name>-props`
     through generated `t/ann` forms, and the defn-typed.core vars a checked expansion calls;
   - `install-fn!` does the same for one function;
   - `check-form!` checks one top-level form without evaluating it and returns its type errors
     as data, each marked whether it involves a defn-typed function;
   - `could-not-type?` tells a message saying the checker could not type the code from a type error.
   defn-typed.core loads this namespace itself when Typed Clojure is on the classpath: each
   defn-typed definition is then checked as it loads (`:typed-check` in defn-typed.edn)."
  (:require [clojure.string :as str]
            [defn-typed.core :refer [defn-typed defnt defmeta schema-props walk-rows]]
            [malli.core :as m]
            [malli.instrument :as mi]
            [typed.clj.checker :as checker]
            [typed.clojure :as t]
            [typed.malli.schema-to-type :as s->t]))

(def lib-anns
  "defn-typed.core vars a checked expansion calls: `row-value` (the binding of a row read at call
   time) and `register-tests!` (the legacy `tests`). Trusted, not checked (`^:no-check`)."
  `[(t/ann ~(with-meta 'defn-typed.core/row-value {:no-check true}) [t/Any t/Any :-> t/Any])
    (t/ann ~(with-meta 'defn-typed.core/register-tests! {:no-check true}) [t/Any t/Any :-> t/Any])])

(defn- type-of
  "The Typed Clojure type syntax of a malli schema, as typed.malli's var-type provider builds it."
  [schema]
  (s->t/malli->type (m/schema schema) {::s->t/mode :validator-type}))

(defn- defn-typed-fn
  "{:name :props :out} of the var v when it is a defn-typed function (a `<name>-props` var beside
   it and a `:malli/schema` of one map argument), else nil."
  [v]
  (let [sym (symbol v)
        schema (:malli/schema (meta v))
        props-var (resolve (symbol (namespace sym) (str (name sym) "-props")))]
    (when (and props-var (vector? schema) (= :=> (first schema)))
      {:name sym :props @props-var :out (peek schema)})))

(defn- required-if-default
  "row without `:optional` when its type carries `:default` in its own props: the body sees the
   key filled."
  [[k row-props type :as row]]
  (if (and (map? row-props) (contains? (schema-props type) :default))
    (let [row-props (not-empty (dissoc row-props :optional))]
      (if row-props [k row-props type] [k type]))
    row))

(defn- fn-anns
  "The `t/ann` forms of the defn-typed function f (defn-typed-fn): `<name>-props` as t/Any, the
   body fn `<name>--positional` (the rows' types in entry order, a row optional without a default
   nilable, typed as the body sees them: every defaulted key present, at any depth) or
   `<name>--body` (the map), each returning the output's type."
  [{:keys [name props out]}]
  (let [sibling #(symbol (namespace name) (str (clojure.core/name name) %))
        filled (walk-rows required-if-default props)
        rows (m/children (m/schema filled))
        row-types (for [[_ entry-props schema] rows
                        :let [row-type (type-of schema)]]
                    (if (:optional entry-props)
                      `(t/Nilable ~row-type)
                      row-type))
        positional (resolve (sibling "--positional"))
        body (resolve (sibling "--body"))
        params (cond
                 positional (vec row-types)
                 body [(type-of props)]
                 ;; the body is in name itself, which the provider types
                 :else nil)]
    (cond-> [`(t/ann ~(sibling "-props") t/Any)]
      params (conj `(t/ann ~(symbol (or positional body)) ~(conj params :-> (type-of out)))))))

(defn- defn-typed-fns
  "defn-typed-fn of every var interned in ns-sym that is a defn-typed function, sorted by name."
  [ns-sym]
  (keep (comp defn-typed-fn val) (sort-by key (ns-interns ns-sym))))

(defn- eval-anns!
  "Evaluates the `t/ann` forms of the defn-typed.core vars a checked expansion calls (lib-anns),
   then those of fns (defn-typed-fn), each in its function's namespace. Returns how many."
  [fns]
  (let [forms (concat (for [ann lib-anns] ['defn-typed.core ann])
                      (for [f fns
                            ann (fn-anns f)]
                        [(symbol (namespace (:name f))) ann]))]
    (doseq [[ns-sym ann] forms]
      (binding [*ns* (the-ns ns-sym)]
        (eval ann)))
    (count forms)))

(defmeta install!
  {:doc "Makes Typed Clojure know every defn-typed function of `namespaces` (loaded symbols): collects
         their malli function schemas (typed.malli's var-type provider, registered by its
         typedclojure_config on the classpath, types each function from them) and evaluates the
         `t/ann` forms of their `<name>--positional` / `<name>--body` and `<name>-props`, and of
         the defn-typed.core vars a checked expansion calls. Run it again after a namespace is
         reloaded. Returns the number of `t/ann` forms evaluated."})

;; no cases: it registers annotations in the checker's global environment
(defn-typed install! {:namespaces [:sequential :symbol]} -> :int
  (mi/collect! {:ns namespaces})
  (eval-anns! (mapcat defn-typed-fns namespaces))
)

(defmeta install-fn!
  {:doc "install! for the one defn-typed function held by `fn-var`: collects its malli function
         schema and evaluates the `t/ann` forms of its `<name>--positional` / `<name>--body` and
         `<name>-props`, and of the defn-typed.core vars a checked expansion calls. What
         defn-typed.core runs right after a definition it type-checks. Returns the number of
         `t/ann` forms evaluated (those of the defn-typed.core vars only, when fn-var is not a
         defn-typed function)."})

;; no cases: it registers annotations in the checker's global environment
(defn-typed install-fn! {:fn-var :any} -> :int
  (mi/-collect! fn-var)
  (eval-anns! (keep defn-typed-fn [fn-var]))
)

(def could-not-type
  "Checker messages that say Typed Clojure could not type the code, not that the code has a type
   error, so defn-typed.core drops them from its definition check (and a tool reporting checker
   findings can drop them the same way, through could-not-type?):
   - `Unannotated var …` — the code calls a var the checker has no type for (a plain defn of the
     project, a library fn without annotations);
   - `Loop requires more annotations` — a loop whose binding types it cannot infer;
   - `Missing type for binding: …` — a `binding` of a dynamic var it has no type for."
  #"^(Unannotated var|Loop requires more annotations)|Missing type for binding: ")

(defn could-not-type?
  "Whether a checker message (check-form!'s `:message`, its `input of`/`output of` label
   included) is one of could-not-type."
  [message]
  (boolean (re-find could-not-type (str/replace-first message #"^(?:input|output) of \S+: " ""))))

(defn- defn-typed-var?
  "Whether sym, resolved in ns-sym, is a defn-typed function or its `<name>--positional` /
   `<name>--body`."
  [ns-sym sym]
  (when-let [v (and (symbol? sym) (try (ns-resolve ns-sym sym) (catch Exception _ nil)))]
    (when (var? v)
      (boolean (or (defn-typed-fn v)
                   (when-let [[_ base] (re-matches #"(.+)--(?:positional|body)" (name (symbol v)))]
                     (some-> (resolve (symbol (namespace (symbol v)) base)) defn-typed-fn)))))))

(defn- defn-typed-form?
  "Whether form is a `(defn-typed …)` or `(defnt …)` form of ns-sym."
  [ns-sym form]
  (and (seq? form) (contains? #{#'defn-typed #'defnt} (try (ns-resolve ns-sym (first form)) (catch Exception _ nil)))))

(defn- labelled
  "{:message :kind} of the checker's message for an error in form of ns-sym: a call of a defn-typed
   function it could not apply (`Function <f> could not be applied…`, f resolving in ns-sym to a
   defn-typed function) = `input of <f>: ` before the message, :input; a `Type mismatch:` in a
   `(defn-typed <f> …)` / `(defnt <f> …)` form = `output of <f>: `, :output; anything else = the message, nil."
  [ns-sym form message]
  (let [[_ applied] (re-find #"^Function (\S+) could not be applied" message)
        applied-var (when applied (try (ns-resolve ns-sym (symbol applied)) (catch Exception _ nil)))]
    (cond
      (and (var? applied-var) (defn-typed-fn applied-var))
      {:message (str "input of " applied ": " message) :kind :input}

      (and (str/starts-with? message "Type mismatch:") (defn-typed-form? ns-sym form))
      {:message (str "output of " (second form) ": " message) :kind :output}

      ;; an error that is neither: the checker's text as it is
      :else {:message message :kind nil})))

(defmeta check-form!
  {:doc "Type-checks one top-level `form` of the namespace `ns` (loaded, `install!`ed) without
         evaluating it (`:check-form-eval :never`: the default re-evaluates the form, which drops
         malli's instrumentation of what it redefines). `file` = the source path the errors name.
         Returns every type error: `{:file :line :column :message :kind :defn-typed?}`: `:message`
         = the checker's text, led by `input of <f>: ` when it could not apply the defn-typed
         function f to the call's map (`:kind :input`) or by `output of <f>: ` for a type mismatch
         in the defn-typed definition of f (`:kind :output`), `:kind` nil otherwise; `:defn-typed?`
         true when the error is inside a `(defn-typed …)` / `(defnt …)` form (its body) or its form calls a
         defn-typed function. A checker crash (StackOverflowError on a very large form) is one
         error of its message."})

;; no cases: the result depends on what install! registered in the checker's environment
(defn-typed check-form! {
  :ns   :symbol
  :form :any
  :file [{:optional true} :string]
} -> [:vector [:map [:file [:maybe :string]] [:line [:maybe :int]] [:column [:maybe :int]]
                    [:message :string] [:kind [:maybe [:enum :input :output]]] [:defn-typed? :boolean]]]
  (let [out (java.io.StringWriter.)
        result (binding [*ns* (the-ns ns)
                         *file* (or file *file*)
                         *out* out
                         *err* out]
                 (try (checker/check-form-info form :check-config {:check-form-eval :never})
                      (catch Throwable e {:ex e})))
        errors (concat (:delayed-errors result)
                       (when-let [e (:ex result)]
                         (or (seq (:errors (ex-data e))) [e])))
        in-defn-typed? (defn-typed-form? ns form)]
    (mapv (fn [e]
            (let [{:keys [env] error-form :form} (ex-data e)]
              (merge {:file (:file env)
                      :line (:line env)
                      :column (:column env)}
                     (labelled ns form (str/trim (or (ex-message e) (str (class e)))))
                     {:defn-typed? (boolean (or in-defn-typed?
                                                (some #(defn-typed-var? ns %) (tree-seq coll? seq error-form))))})))
          errors))
)
