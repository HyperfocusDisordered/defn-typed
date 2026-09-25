(ns hooks.inout
  "clj-kondo hooks for bikes.auction.inout. defnmalli: rewrites
   (defnmalli name ^{table-props}? {key schema …} -> <out-schema> body…) into the def + defn it expands to,
   the row keys bound as locals (and `^{:as sym}` on the map as the whole map), so the name, the
   schemas and the body lint like any defn. defmeta
   (written above the function): rewrites (defmeta name {…}) into (do (declare name) {…}), as the
   macro declares it, so every symbol inside the map (the :inout-tests pairs included) is resolved
   like any other code."
  (:require [clj-kondo.hooks-api :as api]))

(defn- arg-vector?
  "Mirrors bikes.auction.inout/arg-vector?: a vector of binding forms (symbols, destructuring maps)
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

(defn- split-as
  "The input map's reader-meta nodes without their `:as` entry, and that entry's value node: `:as`
   binds the whole map (the macro's own :as), it is not a table prop."
  [meta-nodes]
  (let [as? #(and (api/keyword-node? (first %)) (= :as (api/sexpr (first %))))
        pairs #(partition 2 (:children %))]
    [(map #(if (api/map-node? %) (api/map-node (mapcat identity (remove as? (pairs %)))) %) meta-nodes)
     (some #(when (api/map-node? %) (second (first (filter as? (pairs %))))) meta-nodes)]))

(defn defnmalli [{:keys [node]}]
  (let [[fn-name & more] (rest (:children node))
        [doc more] (if (api/string-node? (first more)) [(first more) (rest more)] [nil more])
        arrow? #(and (api/token-node? %) (= '-> (api/sexpr %)))
        [input [arrow out-schema & body]] (split-with (complement arrow?) more)
        table (first input)
        table? (and table (not (next input)) (api/map-node? table))
        finding! #(api/reg-finding! (assoc (meta %1) :message (str "defnmalli: " %2) :type :syntax))
        ;; {key schema …} → the [key props? schema] rows the macro builds; `key [props schema]` = a row with props
        rows (when table?
               (for [[k v] (partition 2 (:children table))]
                 (let [with-props? (and (api/vector-node? v) (api/map-node? (first (:children v))))]
                   (when-not (api/keyword-node? k)
                     (finding! k "a key of the input map is a keyword"))
                   (when (and with-props? (not= 2 (count (:children v))))
                     (finding! v "a row with props is key [props schema]"))
                   (api/vector-node (cons k (if with-props? (:children v) [v]))))))
        ;; an input of the wrong shape is still analysed, and its vector rows still bind locals, so
        ;; only the shape is reported
        row-nodes (if table?
                    rows
                    (filter #(and (api/vector-node? %) (api/keyword-node? (first (:children %))))
                            (mapcat #(when (api/vector-node? %) (:children %)) input)))
        [table-meta whole] (when table? (split-as (:meta table)))
        props (api/token-node (symbol (str (api/sexpr fn-name) "-props")))
        m (api/token-node 'm__defnmalli)
        ;; the row keys as locals, also bound once to `_` so a row the body does not read reports
        ;; nothing (the macro binds every row, whether the body reads it or not)
        locals (keep #(let [k (first (:children %))]
                        (when (api/keyword-node? k) (api/token-node (symbol (name (api/sexpr k))))))
                     row-nodes)]
    (when doc
      (finding! doc "docstring goes to defmeta"))
    (when-not arrow
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
                                                         (if table? (concat table-meta rows) input)))])
                (api/list-node
                  (concat [(api/token-node 'defn) fn-name]
                          (when doc [doc])
                          [(api/map-node [(api/keyword-node :malli/schema)
                                          (api/vector-node [(api/keyword-node :=>)
                                                            (api/vector-node [(api/keyword-node :cat) props])
                                                            out-schema])])
                           (api/vector-node [m])
                           (api/list-node
                             (concat [(api/token-node 'let)
                                      (api/vector-node [(api/map-node (concat [(api/keyword-node :keys) (api/vector-node locals)]
                                                                                (when whole [(api/keyword-node :as) whole])))
                                                        m
                                                        (api/token-node '_) (api/vector-node locals)])]
                                     body))]))])
             (meta node))}))

(defn defmeta [{:keys [node]}]
  (let [[fn-name m] (rest (:children node))]
    (when-not (and m (api/map-node? m))
      (api/reg-finding! (assoc (meta (or m node))
                               :message "defmeta: the metadata must be a map literal"
                               :type :syntax)))
    {:node (with-meta
             (api/list-node (concat [(api/token-node 'do)
                                     (api/list-node [(api/token-node 'declare) fn-name])]
                                    (when m [m])))
             (meta node))}))
