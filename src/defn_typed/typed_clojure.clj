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
   - `could-not-type?` tells a message saying the checker could not type the code from a type error;
   - `install-cljs!` / `check-cljs-form!` do what install! / check-form! do for ClojureScript
     namespaces, through typed.cljs.checker (with `org.typedclojure/typed.cljs.checker` and
     `org.clojure/clojurescript` on the classpath too; loaded on their first call).
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
  "{:name :props :out :positional :body} of the var v when it is a defn-typed function (a
   `<name>-props` var beside it and a `:malli/schema` of one map argument), else nil; :positional /
   :body = the symbol of `<name>--positional` / `<name>--body` where the body lives, nil when absent."
  [v]
  (let [sym (symbol v)
        schema (:malli/schema (meta v))
        sibling #(resolve (symbol (namespace sym) (str (name sym) %)))
        props-var (sibling "-props")]
    (when (and props-var (vector? schema) (= :=> (first schema)))
      {:name sym :props @props-var :out (peek schema)
       :positional (some-> (sibling "--positional") symbol)
       :body (some-> (sibling "--body") symbol)})))

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
  [{:keys [name props out positional body]}]
  (let [filled (walk-rows required-if-default props)
        rows (m/children (m/schema filled))
        row-types (for [[_ entry-props schema] rows
                        :let [row-type (type-of schema)]]
                    (if (:optional entry-props)
                      `(t/Nilable ~row-type)
                      row-type))
        params (cond
                 positional (vec row-types)
                 body [(type-of props)]
                 ;; the body is in name itself, which the provider types
                 :else nil)]
    (cond-> [`(t/ann ~(symbol (namespace name) (str (clojure.core/name name) "-props")) t/Any)]
      params (conj `(t/ann ~(or positional body) ~(conj params :-> (type-of out)))))))

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
  "{:message :kind} of the checker's `message` for an error in `form`: a call of a defn-typed
   function it could not apply (`Function <f> could not be applied…`, `defn-typed-fn?` true of the
   symbol f) = `input of <f>: ` before the message, :input; a `Type mismatch:` in a
   `(defn-typed <f> …)` / `(defnt <f> …)` form (`in-defn-typed?`) = `output of <f>: `, :output;
   anything else = the message, nil."
  [{:keys [form message defn-typed-fn? in-defn-typed?]}]
  (let [[_ applied] (re-find #"^Function (\S+) could not be applied" message)]
    (cond
      (and applied (defn-typed-fn? (symbol applied)))
      {:message (str "input of " applied ": " message) :kind :input}

      (and (str/starts-with? message "Type mismatch:") in-defn-typed?)
      {:message (str "output of " (second form) ": " message) :kind :output}

      ;; an error that is neither: the checker's text as it is
      :else {:message message :kind nil})))

(defn- findings
  "The findings of check-form! / check-cljs-form! from the checker's `result` for `form`
   (check-form-info's map, or {:ex e} when it threw): `defn-typed-fn?` / `defn-typed-var?` tell
   whether a symbol, as written in form's namespace, names a defn-typed function / one or its
   `<name>--positional` / `<name>--body`; `in-defn-typed?` whether form is a `(defn-typed …)` /
   `(defnt …)` form."
  [{:keys [result form defn-typed-fn? defn-typed-var? in-defn-typed?]}]
  (let [errors (concat (:delayed-errors result)
                       (when-let [e (:ex result)]
                         (or (seq (:errors (ex-data e))) [e])))]
    (mapv (fn [e]
            (let [{:keys [env] error-form :form} (ex-data e)]
              (merge {:file (:file env)
                      :line (:line env)
                      :column (:column env)}
                     (labelled {:form form
                                :message (str/trim (or (ex-message e) (str (class e))))
                                :defn-typed-fn? defn-typed-fn?
                                :in-defn-typed? in-defn-typed?})
                     {:defn-typed? (boolean (or in-defn-typed?
                                                (some #(and (symbol? %) (defn-typed-var? %))
                                                      (tree-seq coll? seq error-form))))})))
          errors)))

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
                      (catch Throwable e {:ex e})))]
    (findings {:result result
               :form form
               :defn-typed-fn? #(let [v (try (ns-resolve ns %) (catch Exception _ nil))]
                                  (boolean (and (var? v) (defn-typed-fn v))))
               :defn-typed-var? #(defn-typed-var? ns %)
               :in-defn-typed? (defn-typed-form? ns form)}))
)

;; ClojureScript: the same, through Typed Clojure's ClojureScript checker (typed.cljs.checker and
;; org.clojure/clojurescript on the classpath besides the two above). Its namespaces load on the
;; first call of these functions, so the Clojure functions above never need ClojureScript.

(defn- cljs-var
  "The var named by the qualified symbol sym (a ClojureScript compiler or checker var), its
   namespace loaded on first use."
  [sym]
  (requiring-resolve sym))

(defn- cljs-compiler
  "The ClojureScript compiler state Typed Clojure's checker reads when none is bound."
  []
  @(cljs-var 'typed.cljs.checker.util/default-env))

(defn- in-cljs-ns
  "Calls f with cljs-compiler bound and the analyzer bindings of the ClojureScript namespace
   ns-sym, errors naming file (nil: none)."
  [ns-sym file f]
  (with-bindings {(cljs-var 'cljs.env/*compiler*) (cljs-compiler)}
    ((cljs-var 'typed.cljs.checker.util/with-analyzer-bindings*) ns-sym (or file "NO_FILE") f)))

(defn- cljs-schema
  "A ClojureScript schema form as JVM malli data: keywords, literals, vectors, maps and the symbols
   of malli's predicate schemas (`string?`) as written, a call of a defn-typed.core function the
   macro wrote (`optional-if-defaulted`) applied; any other symbol or call — a ClojureScript var
   the JVM cannot evaluate — `:any`, which types it t/Any."
  [form]
  (cond
    (and (seq? form) (symbol? (first form)) (= "defn-typed.core" (namespace (first form))))
    (apply @(resolve (first form)) (map cljs-schema (rest form)))

    (seq? form) :any
    (symbol? form) (if (contains? (m/default-schemas) form) form :any)
    (map? form) (into {} (map (fn [[k v]] [(cljs-schema k) (cljs-schema v)])) form)
    (vector? form) (mapv cljs-schema form)
    ;; a keyword, string, number, boolean or nil: data as it is
    :else form))

(defn- cljs-defn-typed-name?
  "Whether q, a qualified symbol, names a defn-typed function in the ClojureScript compiler state
   (a `<name>-props` definition beside it and a `:malli/schema` of one map argument)."
  [q]
  (let [defs (get-in @(cljs-compiler) [:cljs.analyzer/namespaces (symbol (namespace q)) :defs])
        schema (get-in defs [(symbol (name q)) :malli/schema])]
    (boolean (and (contains? defs (symbol (str (name q) "-props"))) (vector? schema) (= :=> (first schema))))))

(defn- cljs-defn-typed-fns
  "defn-typed-fn's map of each defn-typed function whose `<name>-props` value form props-forms
   holds ({qualified <name>-props symbol → the form of its value}, as analyzed), read from the
   ClojureScript compiler state: its `:malli/schema` and whether `<name>--positional` /
   `<name>--body` is defined."
  [props-forms]
  (for [[props-sym form] (sort-by key props-forms)
        :let [[_ base] (re-matches #"(.+)-props" (name props-sym))
              ns-name (namespace props-sym)
              defs (get-in @(cljs-compiler) [:cljs.analyzer/namespaces (symbol ns-name) :defs])
              defined #(when (contains? defs (symbol (str base %))) (symbol ns-name (str base %)))]
        :when (and base (cljs-defn-typed-name? (symbol ns-name base)))]
    {:name (symbol ns-name base)
     :props (cljs-schema form)
     :out (cljs-schema (peek (get-in defs [(symbol base) :malli/schema])))
     :positional (defined "--positional")
     :body (defined "--body")}))

(defmeta install-cljs!
  {:doc "install! for ClojureScript: analyzes `namespaces` (symbols of .cljs/.cljc sources on the
         classpath) into Typed Clojure's ClojureScript compiler state, then registers the `t/ann`
         forms of each defn-typed function's `<name>` (from its schema: the ClojureScript checker
         has no working malli provider), `<name>--positional` / `<name>--body` and `<name>-props`,
         and of the defn-typed.core vars a checked expansion calls. A symbol or call in a
         schema (a ClojureScript var the JVM cannot evaluate) types as t/Any. Run it again after a
         source changes. Returns the number of `t/ann` forms registered."})

;; no cases: it analyzes sources and registers annotations in the checker's global environment
(defn-typed install-cljs! {:namespaces [:sequential :symbol]} -> :int
  (let [props-forms (atom {})
        ;; a compiler pass: the value form of each `(def <name>-props …)` the macro wrote
        ;; (^:typed.clojure/ignore), as the analyzer reads it
        capture (fn [_env ast _opts]
                  (when (and (= :def (:op ast)) (:typed.clojure/ignore (meta (:form ast)))
                             (re-matches #".+-props" (name (:name ast))))
                    (swap! props-forms assoc (:name ast) (:form (:init ast))))
                  ast)
        passes-var (cljs-var 'cljs.analyzer/*passes*)]
    (with-bindings {(cljs-var 'cljs.env/*compiler*) (cljs-compiler)
                    passes-var (conj (vec (or @passes-var @(cljs-var 'cljs.analyzer/default-passes))) capture)}
      ((cljs-var 'cljs.compiler/with-core-cljs)
       nil
       #(doseq [ns-sym namespaces]
          ;; analyze-file skips a namespace whose definitions the state already holds: dropping
          ;; them makes it read the current source
          (swap! (cljs-compiler) update-in [:cljs.analyzer/namespaces ns-sym] dissoc :defs)
          ((cljs-var 'cljs.analyzer.api/analyze-file) ((cljs-var 'cljs.util/ns->source) ns-sym)))))
    ;; t/ann expands to cljs.core.typed/ann, whose macro namespace the analyzer finds only once
    ;; the JVM has loaded it; unloaded, the form analyzes as a call and registers nothing
    (require 'cljs.core.typed)
    (let [forms (concat (for [ann lib-anns] ['defn-typed.core ann])
                        (for [{:keys [name props out] :as f} (cljs-defn-typed-fns @props-forms)
                              ann (cons `(t/ann ~name ~(type-of [:=> [:cat props] out])) (fn-anns f))]
                          [(symbol (namespace name)) ann]))]
      (doseq [[ns-sym ann] forms]
        (in-cljs-ns ns-sym nil #((cljs-var 'cljs.analyzer.api/analyze) ((cljs-var 'cljs.analyzer.api/empty-env)) ann)))
      (count forms)))
)

(defmeta check-cljs-form!
  {:doc "check-form! for ClojureScript: type-checks one top-level `form` of the ClojureScript
         namespace `ns` (install-cljs!ed) with typed.cljs.checker, errors naming `file`. Returns
         every type error, as check-form! does."})

;; no cases: the result depends on what install-cljs! registered in the checker's environment
(defn-typed check-cljs-form! {
  :ns   :symbol
  :form :any
  :file [{:optional true} :string]
} -> [:vector [:map [:file [:maybe :string]] [:line [:maybe :int]] [:column [:maybe :int]]
                    [:message :string] [:kind [:maybe [:enum :input :output]]] [:defn-typed? :boolean]]]
  (let [out (java.io.StringWriter.)
        resolve-in-ns #((cljs-var 'typed.cljs.checker.util/resolve-var) ns %)
        in-defn-typed? (boolean (and (seq? form) (symbol? (first form))
                                     (in-cljs-ns ns file #(contains? #{#'defn-typed #'defnt}
                                                                     ((cljs-var 'cljs.analyzer/get-expander)
                                                                      (first form) ((cljs-var 'cljs.analyzer.api/empty-env)))))))
        result (binding [*out* out
                         *err* out]
                 (in-cljs-ns ns file
                             #(try ((cljs-var 'typed.cljs.checker/check-form-info) form :skip-cljs-analyzer-bindings true)
                                   ;; check-form-info returns every Typed Clojure error: what it
                                   ;; throws is a crash of the checker itself, one Internal Error
                                   (catch Throwable e
                                     (let [{:keys [line column]} (meta form)]
                                       {:ex (ex-info (str "Internal Error (" file ":" line ") " (.getName (class e)) ": " (ex-message e))
                                                     {:env {:file file :line line :column column}})})))))]
    (findings {:result result
               :form form
               :defn-typed-fn? #(boolean (some-> (resolve-in-ns %) cljs-defn-typed-name?))
               :defn-typed-var? #(boolean (when-let [q (resolve-in-ns %)]
                                            (cljs-defn-typed-name?
                                             (if-let [[_ base] (re-matches #"(.+)--(?:positional|body)" (name q))]
                                               (symbol (namespace q) base)
                                               q))))
               :in-defn-typed? in-defn-typed?}))
)
