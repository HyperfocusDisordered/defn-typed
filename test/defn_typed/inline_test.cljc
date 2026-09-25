(ns defn-typed.inline-test
  "Call sites of defn-typed functions: every result below is the same whether the call compiles to
   the map call (the switch off: dev, REPL, tests) or to the positional call (on: clj
   `-J-Ddefn-typed.inline=true`, cljs `:advanced`). CI runs this namespace both ways."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.core :refer [defn-typed]]))

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

(defn- literal-caller [] (padded {:a 1}))

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

#?(:clj
   (deftest call-site-expander
     (testing "the :inline expander: off → a host call on the var (never expanded again), on → the positional call"
       (let [form ((:inline (meta #'padded)) '{:a 1})]
         (if inline?
           (is (= `padded--positional (first (last form))))
           (is (= ['.invoke `padded {:a 1}] (vec form))))))))

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
       (is (re-find #"\(order-total …\) :zz — unknown key\n$"
                    (compile-warnings '(order-total {:price 100 :zz 1}))))
       (is (re-find #"\(order-total …\) :price — missing required key\n$"
                    (compile-warnings '(order-total {:qty 2}))))
       (is (re-find #"\(order-total …\) :price \"100\" — should be an integer\n$"
                    (compile-warnings '(order-total {:price "100"}))))
       (is (re-find #"\(order-total …\) :zz — unknown key; :price — missing required key; :discount 101 — should be at most 100\n$"
                    (compile-warnings '(order-total {:zz 1 :discount 101})))))
     (testing "a fitting literal, a non-constant value, and a map that is not a literal print nothing"
       (is (= "" (compile-warnings '(order-total {:price 100 :qty 2}))))
       (is (= "" (compile-warnings '(let [q 0] (order-total {:price 100 :qty q})))))
       (is (= "" (compile-warnings '(let [m {:qty 0}] (order-total m))))))))
