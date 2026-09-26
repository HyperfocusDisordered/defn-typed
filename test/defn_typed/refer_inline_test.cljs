(ns defn-typed.refer-inline-test
  "A literal call of a defn-typed function `:refer`red from another namespace: in a release
   (`:advanced`) build it compiles to the positional call, as an alias call does; in dev it is the
   map call. CI runs it both ways (the :test and the :inline build)."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.referred :refer [order-total greeting tip]]))

(def inline?
  "Whether this build compiled with the switch on."
  (not ^boolean goog.DEBUG))

(defn- total-caller [] (order-total {:price 100 :qty 3}))

(defn- greeting-caller [] (greeting {:who "referred-marker"}))

(defn- tip-caller [] (tip {:amount 250}))

(deftest referred-literal-call-site
  (testing "release: a fitting referred literal calls the positional fn, so a redefinition of the map fn is not seen; dev: it is the map call through the var"
    (with-redefs [order-total (constantly :redefined)
                  greeting (constantly :redefined)
                  tip (constantly :redefined)]
      (is (= (if inline? 300 :redefined) (total-caller)))
      (is (= (if inline? "hi referred-marker!" :redefined) (greeting-caller)))
      (testing "a function defined with defnt: the same"
        (is (= (if inline? 25 :redefined) (tip-caller)))))))

(deftest referred-value-position
  (testing "a referred function in value position is still the map fn"
    (is (= [300 100] (mapv order-total [{:price 100 :qty 3} {:price 100}])))
    (is (= "hi a!" (let [f greeting] (f {:who "a"}))))))
