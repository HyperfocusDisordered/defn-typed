(ns hooks.defn-typed
  "clj-kondo hooks for defn-typed.core. defn-typed: rewrites
   (defn-typed name {key schema …} -> <out-schema> body…) (or with type variables,
   (defn-typed name [T …] {key schema …} -> <out-schema> body…), each T read as :any) into the vars
   it expands to:
   (def <name>-props [:map …]), (defn name [m] (let [{:keys [k…]} m] (<name>--positional k…))) and
   (defn <name>--positional [k…] body…), the row keys bound as locals, so the name, the schemas, the
   body and a direct positional call lint like any defn; a table of more than 20 rows or a body
   whose recur targets the function keeps the body in name, as the macro does. defmeta
   (written above the function): rewrites (defmeta name {…}) into (do (declare name) {…}), as the
   macro declares it, so every symbol inside the map (the :inout-tests pairs included) is resolved
   like any other code."
  (:require [clj-kondo.hooks-api :as api]))

(defn- arg-vector?
  "Mirrors defn-typed.core/arg-vector?: a vector of binding forms (symbols, destructuring maps)
   that holds a destructuring map or is followed by more body."
  [body]
  (let [form (first body)
        items (when (and form (api/vector-node? form)) (map api/sexpr (:children form)))
        destructuring? #(and (map? %)
                             (every? (fn [k] (or (symbol? k) (#{"keys" "strs" "syms"} (name k)) (#{:as :or} k)))
                                     (keys %)))]
    (boolean (and (seq items)
                  (every? #(or (symbol? %) (destructuring? %)) items)
                  (or (some destructuring? items) (next body))))))

(defn- entry-defaults!
  "Mirrors defn-typed.core/entry-default-paths: reports every row (the rows of nested `[:map …]`
   and of the maps a `:sequential`, `:vector` or `:maybe` holds included) whose entry props carry
   `:default`, which belongs in the type's own props."
  [finding! path props type]
  (when (and props (api/map-node? props)
             (some #(and (api/keyword-node? %) (= :default (api/sexpr %))) (take-nth 2 (:children props))))
    (finding! props (str (apply str (interpose " " (map pr-str path)))
                         " · put :default into the schema's props: [:int {:default v}]")))
  (let [head (when (api/vector-node? type) (some-> (first (:children type)) api/sexpr))]
    (cond
      (= :map head)
      (doseq [row (rest (:children type))
              :when (api/vector-node? row)
              :let [[k a b] (:children row)]
              :when (api/keyword-node? k)]
        (entry-defaults! finding! (conj path (api/sexpr k)) (when b a) (or b a)))

      (#{:sequential :vector :maybe} head)
      (entry-defaults! finding! path nil (last (:children type)))

      ;; any other schema holds no rows
      :else nil)))

(defn- self-recur?
  "Mirrors defn-typed.core/self-recur? on the written body: whether node holds a `recur` whose
   target is the enclosing function, one outside a nested loop, fn (`#(…)` included) or letfn
   binding, and outside a quote."
  [node]
  (let [children (:children node)
        head (when (api/list-node? node)
               (let [h (first children)
                     s (when (and h (api/token-node? h)) (api/sexpr h))]
                 (when (symbol? s) (symbol (name s)))))]
    (cond
      (and (api/token-node? node) (= 'recur (api/sexpr node))) true
      (#{:quote :syntax-quote :fn} (api/tag node)) false
      ('#{loop loop* fn fn* quote} head) false
      (= 'letfn head) (boolean (some self-recur? (nnext children)))
      :else (boolean (some self-recur? children)))))

(defn- name-node?
  "Whether node is a symbol token: the function's name."
  [node]
  (and node (api/token-node? node) (symbol? (api/sexpr node))))

(defn- with-any
  "node with every symbol token of vars (the type variables) replaced by `:any`, at any depth, as
   malli reads the macro's `[:any {:defn-typed/type-var \"T\"}]`."
  [vars node]
  (cond
    (and (api/token-node? node) (contains? vars (api/sexpr node))) (with-meta (api/keyword-node :any) (meta node))
    (:children node) (assoc node :children (map #(with-any vars %) (:children node)))
    :else node))

(defn- defn-typed-form [node]
  (let [[written-name & more] (rest (:children node))
        fn-name (if (name-node? written-name) written-name (api/token-node 'defn-typed-unnamed))
        [doc more] (if (api/string-node? (first more)) [(first more) (rest more)] [nil more])
        arrow? #(and (api/token-node? %) (= '-> (api/sexpr %)))
        [input [arrow & after-arrow]] (split-with (complement arrow?) more)
        ;; a vector right before the input map lists the type variables, as in the macro
        [type-vars input] (if (and (api/vector-node? (first input)) (next input)) [(first input) (rest input)] [nil input])
        vars (set (keep #(let [s (api/sexpr %)] (when (simple-symbol? s) s)) (:children type-vars)))
        [out-schema & body] after-arrow
        ;; the symbols the input and the output are written with, before the type variables become :any
        written (fn [] (set (keep #(when (and (api/token-node? %) (symbol? (api/sexpr %))) (api/sexpr %))
                                  (tree-seq :children :children (api/list-node (remove nil? (concat input [out-schema])))))))
        input (map #(with-any vars %) input)
        out-schema (some->> out-schema (with-any vars))
        table (first input)
        table? (and table (not (next input)) (api/map-node? table))
        finding! #(api/reg-finding! (assoc (meta %1) :message (str "defn-typed: " %2) :type :syntax))
        ;; {key schema …} → the [key props? schema] rows the macro builds; `key [props schema]` = a row with props
        rows (when table?
               (for [[k v] (partition 2 (:children table))]
                 (let [with-props? (and (api/vector-node? v) (api/map-node? (first (:children v))))]
                   (when-not (api/keyword-node? k)
                     (finding! k "a key of the input map is a keyword"))
                   (when (and with-props? (not= 2 (count (:children v))))
                     (finding! v "a row with props is key [props schema]"))
                   (when (api/keyword-node? k)
                     (if with-props?
                       (entry-defaults! finding! [(api/sexpr k)] (first (:children v)) (second (:children v)))
                       (entry-defaults! finding! [(api/sexpr k)] nil v)))
                   (api/vector-node (cons k (if with-props? (:children v) [v]))))))
        ;; an input of the wrong shape is still analysed, and its vector rows still bind locals, so
        ;; only the shape is reported
        row-nodes (if table?
                    rows
                    (filter #(and (api/vector-node? %) (api/keyword-node? (first (:children %))))
                            (mapcat #(when (api/vector-node? %) (:children %)) input)))
        props (api/token-node (symbol (str (api/sexpr fn-name) "-props")))
        m (api/token-node 'm__defn-typed)
        ;; the row keys as locals, also bound once to `_` so a row the body does not read reports
        ;; nothing (the macro binds every row, whether the body reads it or not)
        locals (keep #(let [k (first (:children %))]
                        (when (api/keyword-node? k) (api/token-node (symbol (name (api/sexpr k))))))
                     row-nodes)]
    (when-not (name-node? written-name)
      (finding! written-name "the first argument must be the function's name"))
    (when type-vars
      (let [items (map api/sexpr (:children type-vars))
            unused (remove (written) items)]
        (cond
          (not (and (seq items) (every? simple-symbol? items) (apply distinct? items)))
          (finding! type-vars (str "the type variables are a vector of distinct symbols, got " (pr-str (vec items))))
          (seq unused)
          (finding! type-vars (str "no schema of the signature uses the type variable" (when (next unused) "s") " "
                                   (apply str (interpose " " unused))))
          :else nil)))
    (when (and table? (odd? (count (:children table))))
      (finding! table "the input map has a key without a schema: {key schema …}"))
    (doseq [[local ks] (group-by #(name (api/sexpr %)) (filter api/keyword-node? (take-nth 2 (:children (when table? table)))))
            :when (next ks)]
      (finding! (second ks) (str (apply str (interpose " and " (map (comp pr-str api/sexpr) ks))) " both bind " local)))
    (when (and table? (seq (:meta table)))
      (finding! table "metadata on the argument table is not supported; declare data as a row, e.g. {:row :map}"))
    (when doc
      (finding! doc "docstring goes to defmeta"))
    (if arrow
      (when (empty? after-arrow)
        (finding! arrow "no output schema after ->"))
      (finding! node "expected -> between the input rows and the output schema"))
    (cond
      (nil? table) (finding! node "no input rows between the name and ->")
      (not table?) (finding! (or (second input) table) "the input is a map: {key schema …}")
      (empty? rows) (finding! table "no input rows in the map")
      :else nil)
    (when (arg-vector? body)
      (finding! (first body) "args are bound from the rows: drop the argument vector"))
    (let [positional (api/token-node (symbol (str (api/sexpr fn-name) "--positional")))
          ;; the macro's condition for <name>--positional: at most 20 rows and no recur targeting the
          ;; function. The hook sees the body unexpanded, so a recur a user macro produces is invisible
          ;; here: it emits <name>--positional where the macro keeps the body in name. Two keys binding
          ;; one local (reported above; the macro expands to nothing) keep the body in name too, which
          ;; reports no second finding for them.
          positional? (and (<= (count locals) 20)
                           (= (count locals) (count (set (map api/sexpr locals))))
                           (not-any? self-recur? body))
          ;; a form of the expansion, located at the defn-typed call: clj-kondo gives an unlocated list
          ;; the location of the last located list before it (a row's schema, the body)
          form #(with-meta (api/list-node %) (meta node))
          defn-name (fn [call]
                      (form
                        (concat [(api/token-node 'defn) fn-name]
                                (when doc [doc])
                                [(api/map-node [(api/keyword-node :malli/schema)
                                                (api/vector-node [(api/keyword-node :=>)
                                                                  (api/vector-node [(api/keyword-node :cat) props])
                                                                  ;; no output schema (-> missing, or nothing after it) is reported above; a nil node would abort the file's analysis
                                                                  (or out-schema (api/keyword-node :any))])
                                                (api/keyword-node :arglists)
                                                (api/list-node [(api/token-node 'quote)
                                                                (api/list-node [(api/vector-node [(api/map-node [(api/keyword-node :keys) (api/vector-node locals)])])])])])
                                 (api/vector-node [m])
                                 call])))
          body-let #(api/list-node (concat [(api/token-node 'let) (api/vector-node %)] body))
          rows-of-m [(api/map-node [(api/keyword-node :keys) (api/vector-node locals)]) m]]
      {:node (form
               (concat
                 [(api/token-node 'do)
                  (form [(api/token-node 'def) props
                         (api/vector-node (concat [(api/keyword-node :map)]
                                                  (if table? rows input)))])]
                 ;; name's defn before the positional one, so a body that calls name resolves it
                 ;; without (declare name), which after the defmeta's is a redundant declare
                 (if positional?
                   [(form [(api/token-node 'declare) positional])
                    (defn-name (api/list-node [(api/token-node 'let) (api/vector-node rows-of-m)
                                               (api/list-node (cons positional locals))]))
                    (form [(api/token-node 'defn) positional
                           (api/map-node [(api/keyword-node :no-doc) (api/token-node true)])
                           (api/vector-node locals)
                           (body-let [(api/token-node '_) (api/vector-node locals)])])]
                   ;; no positional fn (see positional?): the body stays in name
                   [(defn-name (body-let (concat rows-of-m [(api/token-node '_) (api/vector-node locals)])))])))})))

(defn defn-typed [{:keys [node]}]
  (if (nil? (second (:children node)))
    ;; (defn-typed): nothing to rewrite, the call itself lints as it is
    (do (api/reg-finding! (assoc (meta node) :message "defn-typed: the first argument must be the function's name"
                                 :type :syntax))
        nil)
    (defn-typed-form node)))

(defn defmeta [{:keys [node]}]
  (let [[fn-name m] (rest (:children node))
        named? (name-node? fn-name)]
    (when-not named?
      (api/reg-finding! (assoc (meta (or fn-name node))
                               :message "defmeta: the first argument must be the function's name"
                               :type :syntax)))
    (when-not (and m (api/map-node? m))
      (api/reg-finding! (assoc (meta (or m node))
                               :message "defmeta: the metadata must be a map literal"
                               :type :syntax)))
    {:node (with-meta
             (api/list-node (concat [(api/token-node 'do)]
                                    (when named? [(api/list-node [(api/token-node 'declare) fn-name])])
                                    (when m [m])))
             (meta node))}))
