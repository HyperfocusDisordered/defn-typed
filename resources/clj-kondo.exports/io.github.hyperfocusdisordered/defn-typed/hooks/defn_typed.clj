(ns hooks.defn-typed
  "clj-kondo hooks for defn-typed.core. defn-typed: rewrites
   (defn-typed name {key schema …} -> <out-schema> body…) into the def + defn it expands to,
   the row keys bound as locals, so the name, the schemas and the body lint like any defn. defmeta
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

(defn- name-node?
  "Whether node is a symbol token: the function's name."
  [node]
  (and node (api/token-node? node) (symbol? (api/sexpr node))))

(defn- defn-typed-form [node]
  (let [[written-name & more] (rest (:children node))
        fn-name (if (name-node? written-name) written-name (api/token-node 'defn-typed-unnamed))
        [doc more] (if (api/string-node? (first more)) [(first more) (rest more)] [nil more])
        arrow? #(and (api/token-node? %) (= '-> (api/sexpr %)))
        [input [arrow & after-arrow]] (split-with (complement arrow?) more)
        [out-schema & body] after-arrow
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
    {:node (with-meta
             (api/list-node
               [(api/token-node 'do)
                (api/list-node [(api/token-node 'def) props
                                (api/vector-node (concat [(api/keyword-node :map)]
                                                         (if table? rows input)))])
                (api/list-node
                  (concat [(api/token-node 'defn) fn-name]
                          (when doc [doc])
                          [(api/map-node [(api/keyword-node :malli/schema)
                                          (api/vector-node [(api/keyword-node :=>)
                                                            (api/vector-node [(api/keyword-node :cat) props])
                                                            ;; no output schema (-> missing, or nothing after it) is reported above; a nil node would abort the file's analysis
                                                            (or out-schema (api/keyword-node :any))])])
                           (api/vector-node [m])
                           (api/list-node
                             (concat [(api/token-node 'let)
                                      (api/vector-node [(api/map-node [(api/keyword-node :keys) (api/vector-node locals)])
                                                        m
                                                        (api/token-node '_) (api/vector-node locals)])]
                                     body))]))])
             (meta node))}))

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
