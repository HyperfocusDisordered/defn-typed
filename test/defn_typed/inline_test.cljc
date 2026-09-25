(ns defn-typed.inline-test
  "Call sites of defn-typed functions: every result below is the same whether the call compiles to
   the map call (the switch off: dev, REPL, tests) or to the positional call (on: clj
   `-J-Ddefn-typed.inline=true`, cljs `:advanced`). CI runs this namespace both ways."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.core :refer [defn-typed]]
            ;; the value check of a literal runs when malli is loaded, as a dev/test loader loads it
            #?(:clj [malli.core])))

(def inline?
  "Whether this build compiled with the switch on."
  #?(:clj (= "true" (System/getProperty "defn-typed.inline"))
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

(defn-typed whole ^{:as row} {
  :a :int
  :b [:int {:default 2}]
} -> :any
  [a b row]
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

(defn-typed closed-pair ^{:closed true} {:a :int :b [:int {:max 9 :default 1}]} -> :any
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
  (testing "^{:as row} binds the whole filled map, keys beyond the rows included"
    (is (= [1 2 {:a 1 :b 2 :z 9}] (whole {:a 1 :z 9}))))
  (testing "a row typed by a symbol reads its default from the evaluated schema"
    (is (= 7 (via-symbol {})))
    (is (= 8 (via-symbol {:k 8}))))
  (testing "a default written as a call is evaluated once, at the def"
    (is (identical? (boxed {}) (boxed {})))))

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
         (is (re-find #"\(scaled …\) :x 1 — should be a double\n$" (warnings false)))))))

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
       (is (re-find #"\(closed-pair …\) :zz — unknown key\n$"
                    (compile-warnings '(closed-pair {:a 1 :zz 1}))))
       (is (re-find #"\(order-total …\) :price — missing required key\n$"
                    (compile-warnings '(order-total {:qty 2}))))
       (is (re-find #"\(order-total …\) :price \"100\" — should be an integer\n$"
                    (compile-warnings '(order-total {:price "100"}))))
       (is (re-find #"\(closed-pair …\) :zz — unknown key; :a — missing required key; :b 10 — should be at most 9\n$"
                    (compile-warnings '(closed-pair {:zz 1 :b 10}))))
       (is (re-find #"\(order-total …\) :price — missing required key; :discount 101 — should be at most 100\n$"
                    (compile-warnings '(order-total {:zz 1 :discount 101})))))
     (testing "an open input map takes keys beyond the rows; a key that is not a keyword literal may be any key"
       (is (= "" (compile-warnings '(order-total {:price 100 :zz 1}))))
       (is (= "" (compile-warnings '(whole {:a 1 :z 3}))))
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
