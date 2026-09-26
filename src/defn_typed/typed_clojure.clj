(ns defn-typed.typed-clojure
  "Typed Clojure checks of defn-typed code, with the types the signatures already declare: no
   hand-written annotation per function. Optional: the library does not depend on Typed Clojure;
   require this namespace only with `org.typedclojure/typed.clj.checker` and
   `org.typedclojure/typed.malli` on the classpath.

   - `install!` makes the checker know every defn-typed function of the given namespaces: the
     function itself through typed.malli's var-type provider (which reads malli's function
     schemas, so it collects them), `<name>--positional` (where the body lives) and `<name>-props`
     through generated `t/ann` forms, and the defn-typed.core vars a checked expansion calls;
   - `check-form!` checks one top-level form without evaluating it and returns its type errors
     as data, each marked whether it involves a defn-typed function."
  (:require [clojure.string :as str]
            [defn-typed.core :refer [defn-typed defmeta]]
            [malli.core :as m]
            [malli.instrument :as mi]
            [typed.clj.checker :as checker]
            [typed.clojure :as t]
            [typed.malli.schema-to-type :as s->t]))

(def lib-anns
  "defn-typed.core vars a checked expansion calls: `with-defaults` and `row-value` (the bindings
   of a function whose body stays in the var itself: more than 20 rows, a recur to the function,
   or `:malli-in-prod` with `^{:as row}`) and `register-tests!` (the legacy `tests`). Trusted,
   not checked (`^:no-check`)."
  `[(t/ann ~(with-meta 'defn-typed.core/with-defaults {:no-check true}) [t/Any t/Any :-> t/Any])
    (t/ann ~(with-meta 'defn-typed.core/row-value {:no-check true}) [t/Any t/Any :-> t/Any])
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

(defn- fn-anns
  "The `t/ann` forms of the defn-typed function f (defn-typed-fn): `<name>-props` as t/Any, the
   body fn `<name>--positional` (the rows' types in entry order, a row optional without a default
   nilable, then the whole map for `^{:as row}`) or `<name>--body` (the map), each returning the
   output's type."
  [{:keys [name props out]}]
  (let [sibling #(symbol (namespace name) (str (clojure.core/name name) %))
        rows (m/children (m/schema props))
        row-types (for [[_ entry-props schema] rows
                        :let [row-type (type-of schema)]]
                    (if (and (:optional entry-props) (not (contains? (m/properties schema) :default)))
                      `(t/Nilable ~row-type)
                      row-type))
        positional (resolve (sibling "--positional"))
        body (resolve (sibling "--body"))
        params (cond
                 positional (cond-> (vec row-types)
                              ;; ^{:as row}: the defaults-filled map follows the rows
                              (= (inc (count rows)) (count (first (:arglists (meta positional)))))
                              (conj (type-of props)))
                 body [(type-of props)]
                 ;; the body is in name itself, which the provider types
                 :else nil)]
    (cond-> [`(t/ann ~(sibling "-props") t/Any)]
      params (conj `(t/ann ~(symbol (or positional body)) ~(conj params :-> (type-of out)))))))

(defn- defn-typed-fns
  "defn-typed-fn of every var interned in ns-sym that is a defn-typed function, sorted by name."
  [ns-sym]
  (keep (comp defn-typed-fn val) (sort-by key (ns-interns ns-sym))))

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
  (let [forms (concat (for [ann lib-anns] ['defn-typed.core ann])
                      (for [ns-sym namespaces
                            f (defn-typed-fns ns-sym)
                            ann (fn-anns f)]
                        [ns-sym ann]))]
    (doseq [[ns-sym ann] forms]
      (binding [*ns* (the-ns ns-sym)]
        (eval ann)))
    (count forms))
)

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
  "Whether form is a `(defn-typed …)` form of ns-sym."
  [ns-sym form]
  (and (seq? form) (= #'defn-typed (try (ns-resolve ns-sym (first form)) (catch Exception _ nil)))))

(defmeta check-form!
  {:doc "Type-checks one top-level `form` of the namespace `ns` (loaded, `install!`ed) without
         evaluating it (`:check-form-eval :never`: the default re-evaluates the form, which drops
         malli's instrumentation of what it redefines). `file` = the source path the errors name.
         Returns every type error: `{:file :line :column :message :defn-typed?}`, `:defn-typed?`
         true when the error is inside a `(defn-typed …)` form (its body) or its form calls a
         defn-typed function. A checker crash (StackOverflowError on a very large form) is one
         error of its message."})

;; no cases: the result depends on what install! registered in the checker's environment
(defn-typed check-form! {
  :ns   :symbol
  :form :any
  :file [{:optional true} :string]
} -> [:vector [:map [:file [:maybe :string]] [:line [:maybe :int]] [:column [:maybe :int]]
                    [:message :string] [:defn-typed? :boolean]]]
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
              {:file (:file env)
               :line (:line env)
               :column (:column env)
               :message (str/trim (or (ex-message e) (str (class e))))
               :defn-typed? (boolean (or in-defn-typed?
                                         (some #(defn-typed-var? ns %) (tree-seq coll? seq error-form))))}))
          errors))
)
