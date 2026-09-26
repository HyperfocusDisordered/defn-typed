(ns defn-typed.inline-test
  "Call sites of defn-typed functions: every result below is the same whether the call compiles to
   the map call (the switch off: clj `-J-Ddefn-typed.inline=false`, cljs dev) or to the positional
   call (on: clj by default, cljs `:advanced`). CI runs this namespace both ways."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.core :refer [defn-typed]]
            ;; the value check of a literal runs when malli is loaded, as a dev/test loader loads it
            #?@(:clj [[clojure.java.shell :as shell]
                      [clojure.string :as str]
                      [malli.core]])))

(def inline?
  "Whether this build compiled with the switch on."
  #?(:clj (not= "false" (System/getProperty "defn-typed.inline"))
     :cljs (not ^boolean goog.DEBUG)))

(defn-typed padded {
  :a :int
  :b [:int {:default 2}]
  :c [{:optional true} [:maybe :int]]
} -> :any
  [a b c]
)

(defn-typed nested {:n [:map [:d [:string {:default "x"}]]]} -> :any
  n
)

(def shared-type [:int {:default 7}])

(defn-typed via-symbol {:k shared-type} -> :any
  k
)

(defn-typed boxed {:box [:any {:default (atom 0)}]} -> :any
  box
)

(defn-typed order-total {
  :price    [:int {:min 1}]
  :qty      [:int {:min 1 :default 1}]
  :discount [:int {:min 0 :max 100 :default 0}]
} -> :int
  (quot (* price qty (- 100 discount)) 100)
)

(defn-typed fact {:n :int} -> :int
  (if (zero? n) 1 (* n (fact {:n (dec n)})))
)

(defn-typed fact-by-value {:n :int} -> :int
  (if (zero? n) 1 (* n (let [f fact-by-value] (f {:n (dec n)}))))
)

(defn-typed count-down {:n :int} -> :int
  (if (pos? n) (recur {:n (dec n)}) n)
)

(defn-typed sum-down {:n :int :acc [:int {:default 0}]} -> :int
  (if (pos? n) (recur {:n (dec n) :acc (+ acc n)}) acc)
)

(defn-typed nested-recur {:n :int} -> :any
  (loop [i n acc []] (if (pos? i) (recur (dec i) (conj acc i)) acc))
)

#?(:clj
   (defmacro again
     "A recur written by a macro: the body's recur only shows once expanded."
     [m]
     `(recur ~m)))

#?(:clj
   (defn-typed count-down-by-macro {:n :int} -> :int
     (if (pos? n) (again {:n (dec n)}) n)
   ))

(defn-typed pair {:a :int :b [:int {:max 9 :default 1}]} -> :any
  [a b]
)

(defn-typed shaped {
  :tags [{:optional true} [:vector :keyword]]
  :opts [{:optional true} [:map-of :keyword :int]]
  :f    [{:optional true} [:fn pos?]]
} -> :any
  [tags opts f]
)

(defn-typed scaled {:x :double} -> :any
  x
)

(defn-typed shipped {
  :order   [:map [:price :int] [:qty [:int {:min 1 :default 1}]]]
  :address [:maybe [:map {:closed true} [:city :string]]]
} -> :any
  [order address]
)

(defn-typed cart-total {
  :items [:sequential [:map [:price [:int {:min 1}]] [:qty [:int {:min 1 :default 1}]]]]
} -> :int
  (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))
)

(defn-typed line-total {:item [:map [:price :int] [:qty [:int {:default 1}]]]} -> :int
  (* (:price item) (:qty item))
)

(defn-typed nested-deep {
  :orders [:vector [:map [:lines [:sequential [:maybe [:map [:sku :string] [:qty [:int {:default 1}]]]]]]]]
} -> :any
  orders
)

(defn- literal-caller [] (padded {:a 1}))

;; a cljs number literal is a double; in clj `1` is a long, which :double rejects
#?(:cljs (defn- double-literal-caller [] (scaled {:x 1})))

(deftest defaults-and-nil
  (testing "an absent defaulted row gets its default, an explicit nil stays nil, an optional row reads nil"
    (is (= [1 2 nil] (padded {:a 1})))
    (is (= [1 nil nil] (padded {:a 1 :b nil})))
    (is (= [1 5 6] (padded {:a 1 :b 5 :c 6})))
    (is (= 300 (order-total {:price 100 :qty 3})))
    (is (= 270 (order-total {:qty 3 :discount 10 :price 100})))))

(deftest rows-read-at-call-time
  (testing "a nested map row is filled by its own defaults; absent it stays nil"
    (is (= {:d "x"} (nested {:n {}})))
    (is (= {:d "y"} (nested {:n {:d "y"}})))
    (is (nil? (nested {}))))
  (testing "a row typed by a symbol reads its default from the evaluated schema"
    (is (= 7 (via-symbol {})))
    (is (= 8 (via-symbol {:k 8}))))
  (testing "a default written as a call is evaluated once, at the def"
    (is (identical? (boxed {}) (boxed {})))))

(deftest defaults-inside-sequences
  (testing "a defaulted key of a map inside a :sequential / :vector row is filled: literal calls, literal items, a real map"
    (is (= 100 (cart-total {:items [{:price 100}]})))
    (is (= 250 (cart-total {:items [{:price 100} {:price 50 :qty 3}]})))
    (let [items [{:price 100}]]
      (is (= 100 (cart-total {:items items}))))
    (let [m {:items [{:price 100}]}]
      (is (= 100 (cart-total m))))
    (is (= 100 (cart-total {:items (list {:price 100})})))
    (is (= 0 (cart-total {:items []}))))
  (testing "any depth: a map inside a :maybe inside a :sequential inside a map inside a :vector; nil stays nil, an explicit value stays"
    (is (= [{:lines [{:sku "a" :qty 1} nil {:sku "b" :qty 4}]}]
           (nested-deep {:orders [{:lines [{:sku "a"} nil {:sku "b" :qty 4}]}]})))
    (let [m {:orders [{:lines [{:sku "a"}]}]}]
      (is (= [{:lines [{:sku "a" :qty 1}]}] (nested-deep m)))))
  (testing "a nested map row (line-total) is filled as before"
    (is (= 100 (line-total {:item {:price 100}})))
    (is (= 300 (line-total {:item {:price 100 :qty 3}})))))

(deftest self-call-by-name
  (testing "the body calls its own function by name, in call position and as a value"
    (is (= 120 (fact {:n 5})))
    (is (= 120 (fact-by-value {:n 5})))))

(deftest recur-in-the-body
  (testing "recur targets the function: it recurs with the map, defaults filled again"
    (is (= 0 (count-down {:n 3})))
    (is (= 6 (sum-down {:n 3}))))
  #?(:clj
     (testing "a recur written by a macro in the body targets the function too"
       (is (= 0 (count-down-by-macro {:n 3})))))
  (testing "a recur inside a nested loop targets the loop"
    (is (= [3 2 1] (nested-recur {:n 3})))))

(deftest literals-that-fall-back
  (testing "an unknown key or a missing required key compiles to the map call, with the map call's result"
    (is (= [1 2 nil] (padded {:a 1 :zz 5})))
    (is (= [nil 3 nil] (padded {:b 3}))))
  (testing "a map that is not a literal, apply, and a higher-order use are the map call"
    (let [m {:a 1}]
      (is (= [1 2 nil] (padded m))))
    (is (= [1 2 nil] (apply padded [{:a 1}])))
    (is (= [[1 2 nil] [2 2 nil]] (mapv padded [{:a 1} {:a 2}])))))

(deftest evaluation-order
  (testing "literal values are evaluated once each, in the literal's order"
    (let [log (atom [])
          result (padded {:b (do (swap! log conj :b) 3) :a (do (swap! log conj :a) 1)})]
      (is (= [1 3 nil] result))
      (is (= [:b :a] @log)))))

(deftest literal-call-site
  (testing "switch on: a fitting literal calls the positional fn, so a redefinition of the map fn is not seen; off: it is the map call through the var"
    (with-redefs [padded (constantly :redefined)]
      (is (= (if inline? [1 2 nil] :redefined) (literal-caller))))))

#?(:cljs
   (deftest cljs-number-literal-call-site
     (testing "`1` fits a :double row in cljs: the call is rewritten (switch on) like any fitting literal"
       (with-redefs [scaled (constantly :redefined)]
         (is (= (if inline? 1 :redefined) (double-literal-caller)))))))

#?(:clj
   (deftest cljs-number-literal-check
     (testing "judged as a cljs call, an integer literal fits a :double row; judged as a clj call it does not"
       (let [spec {:name `scaled :props `scaled-props :rows [{:key :x :index 1 :type :double :required true}]}
             warnings (fn [cljs?]
                        (let [err (java.io.StringWriter.)]
                          (binding [*err* err]
                            (defn-typed.core/expand-call {:spec spec :arg {:x 1} :fallback :map-call :cljs? cljs?
                                                          :file "f" :line 1}))
                          (str err)))]
         (is (= "" (warnings true)))
         (is (re-find #"\(scaled …\) :x 1 — should be a double\n$" (warnings false)))))
     (testing "a cljs literal is judged as JS numbers at any depth: an integer-valued number fits :int, an integer fits :double; the rest is still judged"
       (let [warnings (fn [cljs? type value]
                        (let [err (java.io.StringWriter.)
                              spec {:name `scaled :props `scaled-props :rows [{:key :x :index 1 :type type :required true}]}]
                          (binding [*err* err]
                            (with-redefs [scaled-props [:map [:x (eval type)]]]
                              (defn-typed.core/expand-call {:spec spec :arg {:x value} :fallback :map-call :cljs? cljs?
                                                            :file "f" :line 1})))
                          (str err)))]
         (is (= "" (warnings true [:int {:min 1}] 1.0)))
         (is (re-find #":x 1\.0 — should be an integer\n$" (warnings false [:int {:min 1}] 1.0)))
         (is (= "" (warnings true [:vector :double] [1 2])))
         (is (= "" (warnings true [:map-of :keyword :int] {:a 2.0})))
         (is (re-find #":x 1\.5 — should be an integer\n$" (warnings true :int 1.5)))
         (is (re-find #":x \[1 2\] — at 0: should be at least 2\n$" (warnings true [:vector [:double {:min 2}]] [1 2])))
         (is (re-find #":x 10\.0 — should be at most 9\n$" (warnings true [:int {:max 9}] 10.0)))))))

#?(:clj
   (deftest call-site-expander
     (testing "the :inline expander: off → a host call on the var (never expanded again), on → the positional call"
       (let [form ((:inline (meta #'padded)) '{:a 1})]
         (if inline?
           (is (= `padded--positional (first (last form))))
           (is (= ['.invoke `padded {:a 1}] (vec form))))))
     (testing "a key beyond the rows, or one that is not a keyword literal: the map call, switch on or off"
       (is (= ['.invoke `padded '{:a 1 :zz 2}] (vec ((:inline (meta #'padded)) '{:a 1 :zz 2}))))
       (is (= ['.invoke `padded '{k 1}] (vec ((:inline (meta #'padded)) '{k 1})))))))

#?(:clj
   (deftest literal-nested-defaults-at-compile-time
     (testing "switch on: a literal call whose nested literal items leave a defaulted key out compiles to the positional call with the default filled in the literal"
       (when inline?
         (let [form ((:inline (meta #'cart-total)) '{:items [{:price 100}]})]
           (is (= `cart-total--positional (first (last form))))
           (is (= '[[{:price 100 :qty 1}]] (vec (take-nth 2 (rest (second form)))))))))))

#?(:clj
   (defn- with-property
     "Calls f with the JVM system property set to value (nil = unset), restored after."
     [property value f]
     (let [before (System/getProperty property)
           put! #(if % (System/setProperty property %) (System/clearProperty property))]
       (put! value)
       (try (f) (finally (put! before))))))

#?(:clj
   (deftest literal-is-positional-by-default
     (testing "property unset: a fitting literal compiles to the positional call; `false`: the map call through the var"
       (is (= `padded--positional
              ;; *err*: the once-per-JVM instrumentation line, when malli.instrument is loaded
              (first (last (binding [*err* (java.io.StringWriter.)]
                             (with-property "defn-typed.inline" nil #((:inline (meta #'padded)) '{:a 1})))))))
       (is (= ['.invoke `padded '{:a 1}]
              (vec (with-property "defn-typed.inline" "false" #((:inline (meta #'padded)) '{:a 1}))))))))

#?(:clj
   (deftest inline-under-instrumentation-warns-once
     (testing "a literal compiled inline while malli.instrument is loaded prints one stderr line per JVM; `false` prints none"
       (require 'malli.instrument)
       (let [warned (or (some-> (resolve 'defn-typed.core/instrumentation-warned) deref) (atom false))
             before @warned
             err (java.io.StringWriter.)]
         (try
           (reset! warned false)
           (binding [*err* err]
             (with-property "defn-typed.inline" "false" #((:inline (meta #'padded)) '{:a 1}))
             (is (= "" (str err)))
             (with-property "defn-typed.inline" nil #(do ((:inline (meta #'padded)) '{:a 1})
                                            ((:inline (meta #'padded)) '{:a 2}))))
           (is (= (str "defn-typed: literal calls compile to positional calls, instrumentation will not check them"
                       " — set -Ddefn-typed.inline=false in your dev/test alias\n")
                  (str err)))
           (finally (reset! warned before)))))))

#?(:clj
   (deftest redefinition-reaches-compiled-caller
     (testing "a caller compiled before a REPL redefinition of the function's body runs the new body, switch on or off"
       (binding [*ns* (the-ns 'defn-typed.inline-test)]
         (eval '(defn-typed.core/defn-typed redefined {:a :int} -> :any [:old a]))
         (eval '(defn redefined-caller [] (redefined {:a 1})))
         (let [first-result (eval '(redefined-caller))]
           (eval '(defn-typed.core/defn-typed redefined {:a :int} -> :any [:new a]))
           (try
             (is (= [:old 1] first-result))
             (is (= [:new 1] (eval '(redefined-caller))))
             (finally
               (doseq [s '[redefined redefined--positional redefined-props redefined-caller]]
                 (ns-unmap 'defn-typed.inline-test s)))))))))

#?(:clj
   (defn- compile-warnings
     "What compiling form prints to stderr (compiled inside a fn that is never called)."
     [form]
     (let [err (java.io.StringWriter.)]
       (binding [*err* err *ns* (the-ns 'defn-typed.inline-test)]
         (eval (list 'fn [] form)))
       (str err))))

#?(:clj
   (deftest compile-time-literal-checks
     (testing "a literal with an unknown key, a missing required key, or a constant value its row rejects prints one warning; the call still compiles to the map call"
       (is (re-find #"^WARNING defn-typed .*: \(order-total …\) :qty 0 — should be at least 1\n$"
                    (compile-warnings '(order-total {:price 100 :qty 0}))))
       (is (re-find #"\(pair …\) :zz — unknown key\n$"
                    (compile-warnings '(pair {:a 1 :zz 1}))))
       (is (re-find #"\(order-total …\) :price — missing required key\n$"
                    (compile-warnings '(order-total {:qty 2}))))
       (is (re-find #"\(order-total …\) :price \"100\" — should be an integer\n$"
                    (compile-warnings '(order-total {:price "100"}))))
       (is (re-find #"\(pair …\) :zz — unknown key; :a — missing required key; :b 10 — should be at most 9\n$"
                    (compile-warnings '(pair {:zz 1 :b 10}))))
       (is (re-find #"\(order-total …\) :zz — unknown key; :price — missing required key; :discount 101 — should be at most 100\n$"
                    (compile-warnings '(order-total {:zz 1 :discount 101})))))
     (testing "a key beyond the rows is an unknown key; a key that is not a keyword literal may be any key"
       (is (re-find #"^WARNING defn-typed .*: \(order-total …\) :discont — unknown key\n$"
                    (compile-warnings '(order-total {:price 100 :discont 10}))))
       (is (= "" (compile-warnings '(let [k :price] (order-total {k 100})))))
       (is (= "" (compile-warnings '(via-symbol {})))))
     (testing "a reason is `<key> <value> — <text>`: a part inside the value leads with its path; a part malli has no message for reads `does not match <schema as written>`"
       (is (re-find #"\(shaped …\) :tags \[:a 1\] — at 1: should be a keyword\n$"
                    (compile-warnings '(shaped {:tags [:a 1]}))))
       (is (re-find #"\(shaped …\) :opts \{\"x\" 1\} — at \"x\": should be a keyword\n$"
                    (compile-warnings '(shaped {:opts {"x" 1}}))))
       (is (re-find #"\(shaped …\) :f 0 — does not match \[:fn pos\?\]\n$"
                    (compile-warnings '(shaped {:f 0})))))
     (testing "a fitting literal, a non-constant value, and a map that is not a literal print nothing"
       (is (= "" (compile-warnings '(order-total {:price 100 :qty 2}))))
       (is (= "" (compile-warnings '(let [q 0] (order-total {:price 100 :qty q})))))
       (is (= "" (compile-warnings '(let [m {:qty 0}] (order-total m))))))
     (testing "malli not loaded (a server compiling from source): the key checks run, the value check does not load malli"
       (with-redefs [find-ns (fn [sym] (when-not (= 'malli.core sym) (clojure.lang.Namespace/find sym)))]
         (is (= "" (compile-warnings '(order-total {:price 100 :qty 0}))))
         (is (re-find #"\(order-total …\) :price — missing required key\n$"
                      (compile-warnings '(order-total {:qty 0}))))))))

#?(:clj
   (defn- core-var
     "defn-typed.core's var of that name, resolved at run time (nil where the library lacks it)."
     [sym]
     (resolve (symbol "defn-typed.core" (name sym)))))

#?(:clj
   (defn- with-project-settings
     "Calls f with the project's defn-typed.edn read as the map m, the system properties unset."
     [m f]
     (if-let [v (core-var 'project-settings)]
       (with-redefs-fn {v (delay ((core-var 'settings-of-file) (let [file (java.io.File/createTempFile "defn-typed" ".edn")]
                                                                  (spit file (pr-str m))
                                                                  file)))}
         #(with-property "defn-typed.literal-check" nil
            (fn [] (with-property "defn-typed.inline" nil f))))
       (is false "defn-typed.core has no project settings"))))

#?(:clj
   (defn- compile-error
     "The message of the defn-typed error that compiling form throws (compiled inside a fn that is
      never called), nil when it compiles; stderr is dropped."
     [form]
     (binding [*err* (java.io.StringWriter.) *ns* (the-ns 'defn-typed.inline-test)]
       (try (eval (list 'fn [] form)) nil
            (catch Throwable e
              (some #(when (re-find #"^defn-typed[ :]" (str (ex-message %))) (ex-message %))
                    (take-while some? (iterate ex-cause e))))))))

#?(:clj
   (defn- settings-tree
     "A temp directory with the files {relative path content}; returns its java.io.File."
     [files]
     (let [root (.toFile (java.nio.file.Files/createTempDirectory "defn-typed-settings" (make-array java.nio.file.attribute.FileAttribute 0)))]
       (doseq [[path content] files]
         (let [f (java.io.File. root ^String path)]
           (.mkdirs (.getParentFile f))
           (spit f content)))
       root)))

#?(:clj
   (deftest settings-file-lookup
     (testing "defn-typed.edn is looked up from the working dir up through its parents: a parent's file is found, a nearer one wins, none = nil"
       (if-let [settings-file (core-var 'settings-file)]
         (let [root (settings-tree {"defn-typed.edn" "{:literal-check :error}"
                                    "backend/src/.keep" ""
                                    "miniapp/defn-typed.edn" "{:inline false}"})
               empty-root (settings-tree {"a/b/.keep" ""})]
           (is (= (java.io.File. root "defn-typed.edn") (settings-file (java.io.File. root "backend/src"))))
           (is (= (java.io.File. root "defn-typed.edn") (settings-file root)))
           (is (= (java.io.File. root "miniapp/defn-typed.edn") (settings-file (java.io.File. root "miniapp"))))
           (is (nil? (settings-file (java.io.File. empty-root "a/b")))))
         (is false "defn-typed.core has no settings-file")))))

#?(:clj
   (deftest settings-of-a-file
     (testing "no file = the defaults; a file's keys override them; a bad key, value or shape throws naming the file, the key and the allowed values"
       (if-let [settings-of-file (core-var 'settings-of-file)]
         (let [file (fn [content] (let [root (settings-tree {"defn-typed.edn" content})] (java.io.File. root "defn-typed.edn")))
               error (fn [content] (try (settings-of-file (file content)) nil
                                        (catch clojure.lang.ExceptionInfo e (ex-message e))))]
           (is (= {:literal-check :warn :inline true} (settings-of-file nil)))
           (is (= {:literal-check :warn :inline true} (settings-of-file (file "{}"))))
           (is (= {:literal-check :error :inline true} (settings-of-file (file "{:literal-check :error}"))))
           (is (= {:literal-check :warn :inline false} (settings-of-file (file "{:literal-check :warn :inline false}"))))
           (is (re-find #"^defn-typed .*defn-typed\.edn: unknown key :strict — the keys are :literal-check, :inline$"
                        (error "{:strict true}")))
           (is (re-find #"^defn-typed .*defn-typed\.edn: :literal-check must be one of :warn, :error, got :fail$"
                        (error "{:literal-check :fail}")))
           (is (re-find #"^defn-typed .*defn-typed\.edn: :inline must be one of true, false, got \"no\"$"
                        (error "{:inline \"no\"}")))
           (is (re-find #"^defn-typed .*defn-typed\.edn: the settings must be a map, got \[:error\]$"
                        (error "[:error]"))))
         (is false "defn-typed.core has no settings-of-file")))))

#?(:clj
   (deftest literal-check-setting
     (testing "no setting: a failing literal prints its warning and compiles to the map call"
       (with-project-settings {}
         #(do (is (nil? (compile-error '(order-total {:price 100 :qty 0}))))
              (is (re-find #"^WARNING defn-typed .*: \(order-total …\) :qty 0 — should be at least 1\n$"
                           (compile-warnings '(order-total {:price 100 :qty 0})))))))
     (testing "{:literal-check :error}: a failing literal is a compile error with the warning's text; a fitting one compiles"
       (with-project-settings {:literal-check :error}
         #(do (is (re-find #"^defn-typed .*: \(order-total …\) :qty 0 — should be at least 1$"
                           (str (compile-error '(order-total {:price 100 :qty 0})))))
              (is (re-find #"^defn-typed .*: \(pair …\) :zz — unknown key; :a — missing required key; :b 10 — should be at most 9$"
                           (str (compile-error '(pair {:zz 1 :b 10})))))
              (is (re-find #"^defn-typed .*: \(order-total …\) :discont — unknown key$"
                           (str (compile-error '(order-total {:price 100 :discont 10})))))
              (is (nil? (compile-error '(order-total {:price 100 :qty 2}))))
              (is (nil? (compile-error '(let [m {:qty 0}] (order-total m)))))
              (testing "a cljs call site is judged by the same setting"
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^defn-typed f:1: \(order-total …\) :price — missing required key$"
                                      (defn-typed.core/expand-call {:spec {:name `order-total :props `order-total-props
                                                                           :rows [{:key :price :index 1 :type [:int {:min 1}] :required true}]}
                                                                    :arg {} :fallback :map-call :cljs? true
                                                                    :file "f" :line 1})))))))
     (testing "the system property wins over the file: `warn` over :error, `error` over :warn; a bad value throws naming the allowed ones"
       (with-project-settings {:literal-check :error}
         #(with-property "defn-typed.literal-check" "warn"
            (fn [] (is (nil? (compile-error '(order-total {:price 100 :qty 0})))))))
       (with-project-settings {:literal-check :warn}
         #(with-property "defn-typed.literal-check" "error"
            (fn [] (is (re-find #"\(order-total …\) :qty 0 — should be at least 1$"
                                (str (compile-error '(order-total {:price 100 :qty 0}))))))))
       (with-project-settings {}
         #(with-property "defn-typed.literal-check" "strict"
            (fn [] (is (= "defn-typed: the system property defn-typed.literal-check must be one of warn, error, got \"strict\""
                          (compile-error '(order-total {:price 100 :qty 2}))))))))))

#?(:clj
   (deftest inline-setting
     (testing "{:inline false} keeps the map call; the property `defn-typed.inline` wins over the file"
       (let [expand #((:inline (meta #'padded)) '{:a 1})]
         (with-project-settings {:inline false}
           #(do (is (= ['.invoke `padded '{:a 1}] (vec (expand))))
                (is (= `padded--positional
                       (first (last (binding [*err* (java.io.StringWriter.)]
                                      (with-property "defn-typed.inline" "true" expand))))))))
         (with-project-settings {:inline true}
           #(is (= ['.invoke `padded '{:a 1}] (vec (with-property "defn-typed.inline" "false" expand)))))))))

#?(:clj
   (deftest settings-read-from-the-working-dir
     (testing "a JVM compiling in a subdirectory of a project with defn-typed.edn {:literal-check :error} fails on a failing literal and loads a fitting one"
       (let [root (settings-tree {"defn-typed.edn" "{:literal-check :error}" "backend/.keep" ""})
             classpath (->> (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))
                            (map #(.getAbsolutePath (java.io.File. ^String %)))
                            (str/join java.io.File/pathSeparator))
             run (fn [call]
                   (shell/sh "java" "-cp" classpath "clojure.main" "-e"
                             (str "(require 'defn-typed.core) (do (defn-typed.core/defn-typed f {:a :int} -> :any a) nil) " call)
                             :dir (java.io.File. root "backend")))
             failing (run "(f {:b 1})")
             fitting (run "(println (f {:a 1}))")]
         (is (= 1 (:exit failing)))
         (is (re-find #"defn-typed NO_SOURCE_PATH:1: \(f …\) :b — unknown key; :a — missing required key" (:err failing)))
         (is (= [0 "1\n"] [(:exit fitting) (:out fitting)]))))))

#?(:clj
   (deftest nested-literal-checks
     (testing "a map literal inside a map literal is judged by its own [:map …] row, the finding led by the full key path"
       (is (re-find #"^WARNING defn-typed .*: \(shipped …\) :order :price \"x\" — should be an integer\n$"
                    (compile-warnings '(shipped {:order {:price "x"} :address {:city "Hanoi"}}))))
       (is (re-find #"\(shipped …\) :order :price — missing required key\n$"
                    (compile-warnings '(shipped {:order {:qty 2} :address nil}))))
       (is (re-find #"\(shipped …\) :address :zip — unknown key\n$"
                    (compile-warnings '(shipped {:order {:price 1} :address {:city "Hanoi" :zip 1}}))))
       (is (re-find #"\(shipped …\) :order :sku — unknown key\n$"
                    (compile-warnings '(shipped {:order {:price 1 :sku "x"} :address nil}))))
       (is (re-find #"\(shipped …\) :order :qty 0 — should be at least 1\n$"
                    (compile-warnings '(shipped {:order {:price 1 :qty 0} :address nil})))))
     (testing "a nested value that is not data is skipped; its literal siblings are still judged"
       (is (= "" (compile-warnings '(let [p "x"] (shipped {:order {:price p} :address nil})))))
       (is (= "" (compile-warnings '(let [a {:zip 1}] (shipped {:order {:price 1} :address a})))))
       (is (re-find #"\(shipped …\) :order :qty 0 — should be at least 1\n$"
                    (compile-warnings '(let [p "x"] (shipped {:order {:price p :qty 0} :address nil}))))))
     (testing "malli not loaded: the nested key checks run, the nested value check does not"
       (with-redefs [find-ns (fn [sym] (when-not (= 'malli.core sym) (clojure.lang.Namespace/find sym)))]
         (is (= "" (compile-warnings '(shipped {:order {:price "x"} :address nil}))))
         (is (re-find #"\(shipped …\) :order :price — missing required key; :address :zip — unknown key\n$"
                      (compile-warnings '(shipped {:order {} :address {:city "H" :zip 1}}))))))
     (testing "a cljs call site walks the nested rows as written"
       (let [spec {:name `shipped :props `shipped-props
                   :rows [{:key :order :index 1 :type [:map [:price :int]] :required true}]}
             err (java.io.StringWriter.)]
         (binding [*err* err]
           (defn-typed.core/expand-call {:spec spec :arg {:order {:price "x"}} :fallback :map-call :cljs? true
                                         :file "f" :line 1}))
         (is (re-find #"\(shipped …\) :order :price \"x\" — should be an integer\n$" (str err)))))
     (testing "a vector literal under a :sequential / :vector row: each item is judged against the item schema, the path holding its index; a defaulted item key may be absent"
       (is (= "" (compile-warnings '(cart-total {:items [{:price 100}]}))))
       (is (= "" (compile-warnings '(cart-total {:items [{:price 100} {:price 5 :qty 2}]}))))
       (is (re-find #"^WARNING defn-typed .*: \(cart-total …\) :items 0 :price \"x\" — should be an integer\n$"
                    (compile-warnings '(cart-total {:items [{:price "x"}]}))))
       (is (re-find #"^WARNING defn-typed .*: \(cart-total …\) :items 0 :qtty — unknown key\n$"
                    (compile-warnings '(cart-total {:items [{:price 100 :qtty 2}]}))))
       (is (re-find #"^WARNING defn-typed .*: \(cart-total …\) :items 0 :price — missing required key\n$"
                    (compile-warnings '(cart-total {:items [{:qty 2}]}))))
       (is (re-find #"^WARNING defn-typed .*: \(cart-total …\) :items 1 :price 0 — should be at least 1\n$"
                    (compile-warnings '(cart-total {:items [{:price 1} {:price 0}]}))))
       (is (re-find #"\(nested-deep …\) :orders 0 :lines 1 :qty \"2\" — should be an integer\n$"
                    (compile-warnings '(nested-deep {:orders [{:lines [nil {:sku "a" :qty "2"}]}]}))))
       (is (re-find #"\(cart-total …\) :items 0 :qtty — unknown key\n$"
                    (compile-warnings '(cart-total {:items '({:price 1 :qtty 2})}))))
       (is (= "" (compile-warnings '(let [p 1] (cart-total {:items [{:price p}]}))))))
     (testing "malli not loaded: the item key checks still run"
       (with-redefs [find-ns (fn [sym] (when-not (= 'malli.core sym) (clojure.lang.Namespace/find sym)))]
         (is (= "" (compile-warnings '(cart-total {:items [{:price "x"}]}))))
         (is (re-find #"\(cart-total …\) :items 0 :qtty — unknown key; :items 1 :price — missing required key\n$"
                      (compile-warnings '(cart-total {:items [{:price 1 :qtty 2} {:qty 2}]}))))))
     (testing "a cljs call site walks the items against the rows as written: a defaulted item key may be absent"
       (let [spec {:name `cart-total :props `cart-total-props
                   :rows [{:key :items :index 1 :required true
                           :type [:sequential [:map [:price [:int {:min 1}]] [:qty [:int {:min 1 :default 1}]]]]}]}
             warnings (fn [arg]
                        (let [err (java.io.StringWriter.)]
                          (binding [*err* err]
                            (defn-typed.core/expand-call {:spec spec :arg arg :fallback :map-call :cljs? true
                                                          :file "f" :line 1}))
                          (str err)))]
         (is (= "" (warnings {:items [{:price 100}]})))
         (is (re-find #"\(cart-total …\) :items 0 :qtty — unknown key\n$" (warnings {:items [{:price 1 :qtty 2}]})))))
     (testing "{:literal-check :error}: a nested finding is a compile error"
       (with-project-settings {:literal-check :error}
         #(do (is (re-find #"\(shipped …\) :address :zip — unknown key$"
                           (str (compile-error '(shipped {:order {:price 1} :address {:city "H" :zip 1}})))))
              (is (re-find #"\(shipped …\) :order :sku — unknown key$"
                           (str (compile-error '(shipped {:order {:price 1 :sku "x"} :address nil})))))
              (is (re-find #"\(cart-total …\) :items 0 :qtty — unknown key$"
                           (str (compile-error '(cart-total {:items [{:price 100 :qtty 2}]})))))
              (is (nil? (compile-error '(cart-total {:items [{:price 100}]})))))))))
