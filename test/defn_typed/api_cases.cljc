(ns defn-typed.api-cases
  "In/out cases for the pure public functions of defn-typed, written the way a consumer writes them
   (`defmeta` above a `defn-typed`), from outside the library: each function here takes the
   arguments of the library function it covers as one map and returns what that function returns.
   clj runs them through `deftests!`, cljs through `check-ns`; the functions only clj has sit under
   `:clj`."
  (:require [clojure.string :as str]
            [defn-typed.core :as core :refer [defn-typed defmeta]]
            [defn-typed.malli-in-prod :as prod]
            [malli.core :as m]
            [malli.error :as me]
            #?@(:clj [[clj-kondo.core :as kondo]])))

(defmeta path-text
  {:doc "defn-typed.core/path-text: a key path as the loop prints it."
   :inout-tests [[{:path []}           "(value)"]
                 [{:path [:a]}         ":a"]
                 [{:path [:items 1 :price]} ":items 1 :price"]
                 [{:path ["k" :v]}     "\"k\" :v"]]})

(defn-typed path-text {:path :any} -> :string
  (core/path-text path)
)

(defmeta shown-in
  {:doc "defn-typed.core/shown-in: the one argument itself, the args vector for several."
   :inout-tests [[{:args [{:a 1}]}   {:a 1}]
                 [{:args [[1 2]]}    [1 2]]
                 [{:args [1 2]}      [1 2]]
                 [{:args []}         []]]})

(defn-typed shown-in {:args [:vector :any]} -> :any
  (core/shown-in args)
)

(defmeta single-arg-pairs
  {:doc "defn-typed.core/single-arg-pairs: [in expected] pairs as [[in] expected]; a malformed list throws naming the function."
   :inout-tests [[{:pairs []}                         {:pairs []}]
                 [{:pairs [[{:a 1} 3]]}               {:pairs [[[{:a 1}] 3]]}]
                 [{:pairs [[[1 2] 3] [:k :v]]}        {:pairs [[[[1 2]] 3] [[:k] :v]]}]
                 [{:pairs [[1 2 3]]}
                  {:error "defn-typed: f defmeta cases must be a vector of [in expected] pairs, got [1 2 3]"}]
                 [{:pairs [[1]]}
                  {:error "defn-typed: f defmeta cases must be a vector of [in expected] pairs, got [1]"}]
                 [{:pairs '([{:a 1} 3])}
                  {:error "defn-typed: f defmeta cases must be a vector of [in expected] pairs, got ([{:a 1} 3])"}]]})

(defn-typed single-arg-pairs {:pairs :any} -> :map
  (try {:pairs (core/single-arg-pairs 'f pairs)}
       (catch #?(:clj Exception :cljs :default) e {:error (ex-message e)}))
)

(defmeta doubled
  {:doc "A function with cases for the var functions below to read."
   :inout-tests [[{:n 1} 2]
                 [{:n 2} 4]]})

(defn-typed doubled {:n :int} -> :int
  (* 2 n)
)

(defn- no-cases [] nil)

(defmeta var-name
  {:doc "defn-typed.core/var-name: a var's qualified symbol."
   :inout-tests [[{:v (var doubled)}   'defn-typed.api-cases/doubled]
                 [{:v (var no-cases)}  'defn-typed.api-cases/no-cases]]})

(defn-typed var-name {:v :any} -> :symbol
  (core/var-name v)
)

(defmeta has-cases?
  {:doc "defn-typed.core/has-cases?: whether a var carries in/out cases."
   :inout-tests [[{:v (var doubled)}   true]
                 [{:v (var no-cases)}  false]]})

(defn-typed has-cases? {:v :any} -> :boolean
  (core/has-cases? v)
)

(defmeta var-cases
  {:doc "defn-typed.core/var-cases: [[i args expected] …] of a var's cases, args = the argument list the function is called with."
   :inout-tests [[{:v (var doubled)}   [[0 [{:n 1}] 2] [1 [{:n 2}] 4]]]
                 [{:v (var no-cases)}  []]]})

(defn-typed var-cases {:v :any} -> :any
  (vec (core/var-cases v))
)

(defmeta case-vars
  {:doc "defn-typed.core/case-vars: the distinct vars among a list that have cases, sorted by name (shown here by name)."
   :inout-tests [[{:vars []}                                                  []]
                 [{:vars [(var no-cases)]}                                    []]
                 [{:vars [(var no-cases) (var doubled) (var doubled)]}        ['defn-typed.api-cases/doubled]]
                 [{:vars [(var path-text) (var doubled) (var no-cases)]}
                  ['defn-typed.api-cases/doubled 'defn-typed.api-cases/path-text]]]})

(defn-typed case-vars {:vars [:vector :any]} -> :any
  (mapv core/var-name (core/case-vars vars))
)

(defmeta tag-rows
  {:doc "defn-typed.core/walk-rows with a row function that marks the row `{:seen true}` in its props."
   :inout-tests [[{:schema :int}                          :int]
                 [{:schema [:maybe :int]}                 [:maybe :int]]
                 [{:schema [:map [:a :int]]}              [:map [:a {:seen true} :int]]]
                 [{:schema [:map [:a {:optional true} :int]]}
                  [:map [:a {:optional true :seen true} :int]]]
                 [{:schema [:map [:n [:map [:b :int]]]]}
                  [:map [:n {:seen true} [:map [:b {:seen true} :int]]]]]
                 [{:schema [:sequential [:map [:q :int]]]}
                  [:sequential [:map [:q {:seen true} :int]]]]
                 [{:schema [:vector [:maybe [:map [:q :int]]]]}
                  [:vector [:maybe [:map [:q {:seen true} :int]]]]]
                 [{:schema [:map {:closed true} [:a :int]]}
                  [:map {:closed true} [:a {:seen true} :int]]]]})

(defn-typed tag-rows {:schema :any} -> :any
  (core/walk-rows (fn [[k a b]] (if b [k (assoc a :seen true) b] [k {:seen true} a])) schema)
)

(defmeta optional-if-defaulted
  {:doc "defn-typed.core/optional-if-defaulted: a row marked optional when its type carries a default; its type's rows the same."
   :inout-tests [[{:row [:a [:int {:default 1}]]}  [:a {:optional true} [:int {:default 1}]]]
                 [{:row [:a :int]}                 [:a :int]]
                 [{:row [:a {:doc "x"} [:int {:default 1}]]}
                  [:a {:doc "x" :optional true} [:int {:default 1}]]]
                 [{:row [:n [:map [:b [:string {:default "x"}]]]]}
                  [:n [:map [:b {:optional true} [:string {:default "x"}]]]]]]})

(defn-typed optional-if-defaulted {:row :any} -> :any
  (core/optional-if-defaulted row)
)

(defmeta row-value
  {:doc "defn-typed.core/row-value: the value with-defaults gives a row's key of a map."
   :inout-tests [[{:row [:a [:int {:default 1}]] :m {}}          1]
                 [{:row [:a [:int {:default 1}]] :m {:a 5}}      5]
                 [{:row [:a [:int {:default 1}]] :m {:a nil}}    nil]
                 [{:row [:a :int] :m {}}                         nil]
                 [{:row [:n [:map [:b [:string {:default "x"}]]]] :m {:n {}}} {:b "x"}]
                 [{:row [:s [:sequential [:map [:q [:int {:default 1}]]]]] :m {:s [{} {:q 2}]}}
                  [{:q 1} {:q 2}]]]})

(defn-typed row-value {:row :any :m :map} -> :any
  (core/row-value row m)
)

(defmeta malli-in-prod-opts-problem
  {:doc "defn-typed.core/malli-in-prod-opts-problem: why a defmeta's :malli-in-prod value is invalid, nil when it is valid."
   :inout-tests [[{:v true}                              nil]
                 [{:v false}                             nil]
                 [{:v {}}                                nil]
                 [{:v {:sample 0.01 :redact #{:phone}}}  nil]
                 [{:v {:sample 1}}                       nil]
                 [{:v {:sample 0}}                       ":sample is a number 0 < x ≤ 1, got 0"]
                 [{:v {:sample 1.5}}                     ":sample is a number 0 < x ≤ 1, got 1.5"]
                 [{:v {:sample "x"}}                     ":sample is a number 0 < x ≤ 1, got \"x\""]
                 [{:v {:redact [:phone]}}                ":redact is a set of keys, got [:phone]"]
                 [{:v {:sampel 0.5}}                     "takes :sample and :redact, got (:sampel)"]
                 [{:v :yes}                              "true or a map {:sample 0<x≤1 :redact #{key …}}, got :yes"]
                 [{:v nil}                               "true or a map {:sample 0<x≤1 :redact #{key …}}, got nil"]]})

(defn-typed malli-in-prod-opts-problem {:v :any} -> :any
  (core/malli-in-prod-opts-problem v)
)

(defn- invalid-input [schema args]
  {:type :malli.core/invalid-input :data {:input schema :args args}})

(defmeta malli-reasons
  {:doc "defn-typed.core/malli-reasons: one `<key path> · <message> · got <value>` line per failing key of a malli rejection; nil for any other exception."
   :inout-tests [[{:ex (invalid-input [:cat [:map [:a :int]]] [{:a "x"}])}
                  [":a · should be an integer · got \"x\""]]
                 [{:ex (invalid-input [:cat [:map [:a :int]]] [{}])}
                  [":a · missing required key · missing"]]
                 [{:ex (invalid-input [:cat [:map [:items [:vector [:map [:price :int]]]]]]
                                      [{:items [{:price 1} {:price "x"}]}])}
                  [":items 1 :price · should be an integer · got \"x\""]]
                 [{:ex (invalid-input [:cat :int :int] [1 "y"])}
                  ["1 · should be an integer · got \"y\""]]
                 [{:ex {:type :malli.core/invalid-output :data {:output :int :value "s"}}}
                  ["(value) · should be an integer · got \"s\""]]
                 [{:ex {:type :other :data {}}}      nil]
                 [{:ex {}}                           nil]]})

(defn-typed malli-reasons {:ex :map} -> :any
  (core/malli-reasons {:explain m/explain :error-message me/error-message} ex)
)

(defmeta redact
  {:doc "defn-typed.malli-in-prod/redact: the keys removed from every map inside a value, at any depth."
   :inout-tests [[{:ks #{:phone} :value {:phone "1" :name "a"}}              {:name "a"}]
                 [{:ks #{:phone} :value {:users [{:phone "1" :id 1} {:id 2}]}} {:users [{:id 1} {:id 2}]}]
                 [{:ks #{:a :b} :value {:a 1 :b 2 :c {:a 3 :d 4}}}           {:c {:d 4}}]
                 [{:ks #{} :value {:phone "1"}}                              {:phone "1"}]
                 [{:ks nil :value {:phone "1"}}                              {:phone "1"}]
                 [{:ks #{:phone} :value 5}                                   5]
                 [{:ks #{:phone} :value nil}                                 nil]]})

(defn-typed redact {:ks :any :value :any} -> :any
  (prod/redact ks value)
)

(defmeta malli-check?
  {:doc "defn-typed.core/malli-check?: whether a call is checked: a checker, and no :sample or a draw under it."
   :inout-tests [[{:checker nil}                                          false]
                 [{:checker {}}                                           true]
                 [{:checker {:sample nil}}                                true]
                 [{:checker {:sample 0.5 :sampled? (constantly true)}}    true]
                 [{:checker {:sample 0.5 :sampled? (constantly false)}}   false]]})

(defn-typed malli-check? {:checker :any} -> :boolean
  (core/malli-check? checker)
)

(defmeta malli-check!
  {:doc "defn-typed.core/malli-check!: hands the direction and the value to the checker's check! fn and returns what it returns."
   :inout-tests [[{:checker {:check! (fn [direction value] [direction value])} :direction :input :value {:a 1}}
                  [:input {:a 1}]]
                 [{:checker {:check! (fn [_ _] nil)} :direction :output :value 5}
                  nil]]})

(defn-typed malli-check! {:checker :any :direction :keyword :value :any} -> :any
  (core/malli-check! checker direction value)
)

(defmeta malli-checker
  {:doc "defn-typed.core/malli-checker: the checker a slot already holds."
   :inout-tests [[{:slot-state {:checker :built}}                          :built]
                 [{:slot-state {:checker {:sample 0.5} :spec {:fn 'f}}}   {:sample 0.5}]]})

(defn-typed malli-checker {:slot-state :map} -> :any
  (core/malli-checker (atom slot-state))
)

(defmeta malli-in-prod-slot
  {:doc "defn-typed.core/malli-in-prod-slot: the holder of a :malli-in-prod function, its spec and nothing else until the first call."
   :inout-tests [[{:spec {:fn 'f :input [:map] :output :int :opts nil}}
                  {:spec {:fn 'f :input [:map] :output :int :opts nil}}]]})

(defn-typed malli-in-prod-slot {:spec :map} -> :map
  @(core/malli-in-prod-slot spec)
)

#?(:clj
   (defmeta settings-of-file
     {:doc "defn-typed.core/settings-of-file: the settings a defn-typed.edn text gives, the defaults for what it leaves out; a bad file throws naming the file."
      :inout-tests [[{:text "{}"}
                     {:settings {:dev false :literal-check :warn :unknown-keys :warn :inline true :stale-callers :warn
                                 :typed-check :warn :typed-check-cache :on :inout-check :warn :report-success false}}]
                    [{:text "{:literal-check :error :inline false}"}
                     {:settings {:dev false :literal-check :error :unknown-keys :warn :inline false :stale-callers :warn
                                 :typed-check :warn :typed-check-cache :on :inout-check :warn :report-success false}}]
                    [{:text "{:report-success true :stale-callers :off}"}
                     {:settings {:dev false :literal-check :warn :unknown-keys :warn :inline true :stale-callers :off
                                 :typed-check :warn :typed-check-cache :on :inout-check :warn :report-success true}}]
                    [{:text "{:strict true}"} {:error-after-file "unknown key :strict — the keys are :dev, :literal-check, :unknown-keys, :inline, :stale-callers, :typed-check, :typed-check-cache, :inout-check, :report-success"}]
                    [{:text "{:literal-check :fail}"} {:error-after-file ":literal-check must be one of :warn, :error, :off, got :fail"}]
                    [{:text "[:error]"} {:error-after-file "the settings must be a map, got [:error]"}]]}))

#?(:clj
   (defn-typed settings-of-file {:text :string} -> :map
     (let [dir (.toFile (java.nio.file.Files/createTempDirectory "defn-typed-cases" (make-array java.nio.file.attribute.FileAttribute 0)))
           file (java.io.File. dir "defn-typed.edn")]
       (try (spit file text)
            (try {:settings (core/settings-of-file file)}
                 (catch clojure.lang.ExceptionInfo e
                   {:error-after-file (second (str/split (ex-message e) #"defn-typed\.edn: " 2))}))
            (finally (.delete file) (.delete dir))))
   ))

#?(:clj
   (defmeta hook-findings
     {:doc "The exported clj-kondo hooks (resources/clj-kondo.exports/…/hooks/defn_typed.clj) on a snippet: [row type message] per finding."
      :inout-tests [[{:code "(defn-typed f {:a :int} -> :int a)"} []]
                    [{:code "(defn-typed f {:a :int :b [:int {:default 1}]} -> :int (+ a b))\n(f--positional 1 2)"} []]
                    [{:code "(defn-typed f {:a :int} -> :int a)\n(f--positional 1 2)"}
                     [[3 :invalid-arity "k/f--positional is called with 2 args but expects 1"]]]
                    [{:code "(defn-typed f {:a [{:default 1} :int]} -> :int a)"}
                     [[2 :syntax "defn-typed: :a · put :default into the schema's props: [:int {:default v}]"]]]
                    [{:code "(defn-typed f {:a :int :a :string} -> :int a)"}
                     [[2 :syntax "defn-typed: :a and :a both bind a"]]]
                    [{:code "(defmeta f {:inout-tests [[{:a 1} 1]]})\n(defn-typed f {:a :int} -> :int a)"} []]]}))

#?(:clj
   (defn-typed hook-findings {:code :string} -> [:vector :any]
     (->> (with-in-str (str "(ns k (:require [defn-typed.core :refer [defn-typed defnt defmeta]]))\n" code)
            (kondo/run! {:lint ["-"] :lang :clj :cache false
                         :config-dir "resources/clj-kondo.exports/io.github.hyperfocusdisordered/defn-typed"}))
          :findings
          (remove #(= 1 (:row %)))
          (mapv (juxt :row :type :message)))
   ))
