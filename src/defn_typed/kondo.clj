(ns defn-typed.kondo
  "clj-kondo types for the functions of a source tree, read from the source without loading it:
   `(emit! {:src-dirs [\"src\"] :out \".clj-kondo/imports/defn-typed/types/config.edn\"})`, or from a shell
   `clojure -M -m defn-typed.kondo --src-dir src --out .clj-kondo/imports/defn-typed/types/config.edn`.
   Reading the source covers .cljs files, whose schemas exist only at run time in a browser, and
   needs no loaded namespaces, so an after-edit hook can call emit! in any warm JVM."
  (:require [clojure.java.io :refer [file make-parents]]
            [clojure.walk :refer [postwalk prewalk]]
            [defn-typed.core :refer [defaults-optional defmeta defn-typed form-parts]]
            [edamame.core :refer [parse-string parse-string-all]]
            [fipp.edn :refer [pprint]]
            [malli.clj-kondo :refer [from linter-config]]
            [malli.core :refer [function-schema]]))

(def edamame-location-keys
  "The metadata edamame puts on every form it reads (`:all true`): never a table's own props."
  [:row :col :end-row :end-col])

(defn read-source
  "[ns-sym forms] of a source file: every top-level form, read as the compiler of its language
   reads it — a .cljs file its `:cljs` reader-conditional branches, a .clj / .cljc file its `:clj`
   ones; `::k` resolves against the file's own ns, tagged literals read as their plain value."
  [source]
  (let [text (slurp source)
        read-cond {:read-cond :allow
                   :features (if (.endsWith (.getName (file source)) ".cljs") #{:cljs} #{:clj})}
        ns-sym (second (parse-string text (merge {:all true :readers (fn [_] identity) :auto-resolve (fn [a] a)}
                                                 read-cond)))]
    [ns-sym (parse-string-all text (merge {:all true
                                           :readers (fn [_] identity)
                                           :auto-resolve (fn [a] (if (= :current a) ns-sym a))}
                                          read-cond))]))

(defn typed-form?
  "Whether form is a `(defn-typed …)` / `(defnt …)` definition, referred or qualified."
  [form]
  (and (seq? form) (symbol? (first form)) (#{"defn-typed" "defnt"} (name (first form)))))

(defn plain-defn?
  "Whether form is a `(defn …)` / `(defn- …)` definition."
  [form]
  (and (seq? form) (#{'defn 'defn-} (first form))))

(defn typed-form-schema
  "The `[:=> [:cat <input>] <output>]` a defn-typed form gives its function, through the macro's
   own reading of the form (defn-typed.core/form-parts). Metadata on the input map (`^{:as row}`,
   `^{:closed true}`, accepted by 0.3.1, a compile error since) does not stop it: the map is read
   without it, and its keys become the `[:map …]`'s props, as 0.3.1 expanded them. Throws an
   ex-info naming file and function when the form is not a defn-typed form."
  [path form]
  (let [[_ fn-name & more] form
        [head tail] (split-with #(not= '-> %) more)
        table (first (filter map? head))
        table-props (not-empty (apply dissoc (meta table) edamame-location-keys))
        fail! #(throw (ex-info (str "defn-typed.kondo " path ": " fn-name ": " %)
                               {:type :defn-typed.core/invalid-form :file path :fn fn-name}))
        {:keys [input output]} (form-parts fail! (concat (map #(if (map? %) (with-meta % nil) %) head) tail))]
    [:=> [:cat (if table-props (into [:map table-props] (rest input)) input)] output]))

(defn plain-defn-schema
  "The `:malli/schema` of a plain defn form's attr-map (any map before its arg vector), else nil."
  [form]
  (some #(when (map? %) (:malli/schema %)) (take-while (complement vector?) (drop 2 form))))

(defn schema-defs
  "`{ns-sym {def-sym value}}`: every top-level `(def name … value)` of the parsed files, a ns's
   files (.clj and .cljs of one ns) merged."
  [parsed]
  (apply merge-with merge
         (for [[ns-sym forms] parsed]
           {ns-sym (into {} (for [form forms :when (and (seq? form) (= 'def (first form)))]
                              [(second form) (last form)]))})))

(defn referred-namespaces
  "`{ns-sym {sym home-ns}}`: every symbol a ns form `:refer`s by name (`[home :refer [sym …]]`)."
  [parsed]
  (apply merge-with merge
         (for [[ns-sym [ns-form]] parsed]
           {ns-sym (into {} (for [clause (rest ns-form)
                                  :when (and (seq? clause) (= :require (first clause)))
                                  spec (rest clause)
                                  :when (and (vector? spec) (even? (count (rest spec))))
                                  :let [refer (:refer (apply hash-map (rest spec)))]
                                  :when (sequential? refer)
                                  sym refer]
                              [sym (first spec)]))})))

(defn resolved-schema
  "schema of ns-sym with every symbol naming a def of its own ns, else a def of the ns it is
   `:refer`red from, replaced by that def's value (resolved the same way, at any depth), and every
   `[:fn …]` replaced by `:any`: a cljs predicate cannot run on the JVM, and clj-kondo types `:fn`
   as `:any` anyway. Any other symbol stays (malli's predicate names: `string?`, `pos-int?` …)."
  [{:keys [defs refers ns-sym schema]}]
  (let [own (get defs ns-sym)]
    (prewalk (fn [x]
               (let [home (get-in refers [ns-sym x])]
                 (cond
                   (and (vector? x) (= :fn (first x))) :any
                   (and (symbol? x) (contains? own x))
                   (resolved-schema {:defs defs :refers refers :ns-sym ns-sym :schema (get own x)})
                   (and (symbol? x) (contains? (get defs home) x))
                   (resolved-schema {:defs defs :refers refers :ns-sym home :schema (get-in defs [home x])})
                   :else x)))
             schema)))

(defn key-order
  "The order every map of the config prints in: numbers by value, then keywords, then symbols,
   then anything else, each kind by its printed text."
  [a b]
  (let [rank (fn [k] (cond (number? k) 0 (keyword? k) 1 (symbol? k) 2 :else 3))]
    (if (and (number? a) (number? b))
      (compare a b)
      (compare [(rank a) (str a)] [(rank b) (str b)]))))

(defn config-text
  "The clj-kondo config for entries ({:ns :name :schema} each, a later entry of the same ns and
   name replacing an earlier one), as fipp prints it at width 120, every map sorted by key-order:
   the same sources print the same text whatever order the maps were built in."
  [entries]
  (let [config (linter-config (mapcat #(from (update % :schema function-schema)) entries))]
    (with-out-str (pprint (postwalk #(if (map? %) (into (sorted-map-by key-order) %) %) config)
                          {:width 120}))))

(defn function-entries
  "{:ns :name :schema :file} of every typed function under src-dirs, in file-seq order: each
   .clj/.cljs/.cljc file mentioning `:malli/schema` or `defn-typed`, each top-level defn-typed /
   defnt form (typed-form-schema) and each defn / defn- with a `:malli/schema` (plain-defn-schema),
   its schema resolved (resolved-schema); a defn-typed input then marked by defaults-optional
   again, which reaches the defaults a resolved symbol brought in, as the macro's
   optional-if-defaulted does for a row typed by a symbol when it loads."
  [src-dirs]
  (let [parsed (for [src-dir src-dirs
                     source (file-seq (file src-dir))
                     :when (and (.isFile source) (re-find #"\.clj[sc]?$" (.getName source))
                                (let [text (slurp source)] (or (.contains text ":malli/schema") (.contains text "defn-typed"))))]
                 (conj (read-source source) (.getPath source)))
        defs (schema-defs parsed)
        refers (referred-namespaces parsed)]
    (vec (for [[ns-sym forms path] parsed
               form forms
               :let [typed? (typed-form? form)
                     schema (cond typed? (typed-form-schema path form)
                                  (plain-defn? form) (plain-defn-schema form))]
               :when schema
               :let [resolved (resolved-schema {:defs defs :refers refers :ns-sym ns-sym :schema schema})]]
           {:ns ns-sym
            :name (second form)
            :file path
            ;; a defn-typed schema = [:=> [:cat <input>] <output>]
            :schema (if typed? (update-in resolved [1 1] defaults-optional) resolved)}))))

(defmeta emit!
  {:doc "Writes the clj-kondo type config of every typed function under src-dirs to out:
   each defn-typed / defnt form (its signature as the macro reads it, defn-typed.core/form-parts,
   defaulted rows optional) and each plain defn / defn- carrying `:malli/schema`, read from the
   source (function-entries) — a .cljs file through its :cljs reader branches, a .clj / .cljc file
   through its :clj ones. In a schema, a symbol naming a top-level def of the same ns (or of the ns
   it is `:refer`red from) stands for that def's value, and `[:fn …]` for `:any`
   (resolved-schema); metadata on a defn-typed input map becomes the map's props
   (typed-form-schema). malli.clj-kondo turns the signatures into config; every map prints sorted
   by key (config-text), so the same sources give the same text, and out is written only when
   that text differs from the file's. malli's global function-schema registry is never touched,
   so a REPL's own registrations stay as they are. Returns {:written? :fns :out}, :fns the number of
   distinct functions typed. Writes a file, so no in/out cases."})

(defn-typed emit! {
  :src-dirs [:sequential :string]
  :out      :string
} -> [:map [:written? :boolean] [:fns :int] [:out :string]]
  (let [entries (function-entries src-dirs)
        text (config-text entries)
        target (file out)
        written? (not (and (.exists target) (= text (slurp target))))]
    (when written?
      (make-parents target)
      (spit target text))
    {:written? written?
     :fns (count (distinct (map (juxt :ns :name) entries)))
     :out out}))

(def usage
  "The CLI's --help text."
  "Writes the clj-kondo types of the typed functions under the source dirs (defn-typed.kondo/emit!).
  clojure -M -m defn-typed.kondo --src-dir src --src-dir test --out .clj-kondo/imports/defn-typed/types/config.edn")

(defn cli-options
  "{:src-dirs [...] :out path} from the CLI's args, `{:help true}` for --help, `{:error message}`
   for an unknown flag, a flag without its value, or no --src-dir / --out."
  [args]
  (loop [[flag value & more :as args] args
         options {:src-dirs []}]
    (cond
      (empty? args) (cond (empty? (:src-dirs options)) {:error "no --src-dir"}
                          (nil? (:out options)) {:error "no --out"}
                          :else options)
      (= "--help" flag) {:help true}
      (not (#{"--src-dir" "--out"} flag)) {:error (str "unknown flag: " flag)}
      (nil? value) {:error (str flag " takes a value")}
      (= "--src-dir" flag) (recur more (update options :src-dirs conj value))
      :else (recur more (assoc options :out value)))))

(defn -main
  "CLI: emit! with the dirs and the out path of the args (cli-options); one line on stderr, stdout
   stays empty. Exit 0 after `OK <n> fns → <path>`, 2 on bad args (with the --help text)."
  [& args]
  (let [{:keys [help error src-dirs out]} (cli-options args)]
    (binding [*out* *err*]
      (cond
        help (println usage)
        error (do (println (str "defn-typed.kondo: " error "\n" usage))
                  (System/exit 2))
        :else (let [{:keys [fns]} (emit! {:src-dirs src-dirs :out out})]
                (println (str "OK " fns " fns → " out)))))))
