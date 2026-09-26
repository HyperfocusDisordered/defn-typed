(ns defn-typed.readme-test
  "Evaluates README.md's example blocks verbatim (each ```clojure block right after a
   `<!-- readme-test -->` line, in order, the first one's ns form included) and runs the in/out
   cases they declare, so the README examples are code that compiles and passes."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.core :as core]))

(defn readme-examples
  "Texts of README.md's ```clojure blocks marked `<!-- readme-test -->`, in order."
  []
  (map second (re-seq #"(?s)<!-- readme-test -->\n```clojure\n(.*?)```" (slurp "README.md"))))

(deftest readme-examples-compile-and-pass
  (let [blocks (readme-examples)]
    (is (= 4 (count blocks)))
    (binding [*ns* *ns*]
      (load-string (apply str blocks))))
  (testing "their defmeta cases run against their defn-typed fns"
    (is (= [{:var 'example/fizzbuzz :cases 5 :failures []}
            {:var 'example/invite-token-of :cases 5 :failures []}
            {:var 'example/line-total :cases 2 :failures []}
            {:var 'example/order-total :cases 3 :failures []}]
           (core/check-ns 'example))))
  (testing "a call reads the rows as locals"
    (is (= 270 ((resolve 'example/order-total) {:price 100 :qty 3 :discount 10})))
    (is (= "FizzBuzz" ((resolve 'example/fizzbuzz) {:n 30})))
    (is (= "Xy_9-z" ((resolve 'example/invite-token-of) {:url-token nil :start-param "invite-Xy_9-z"})))))
