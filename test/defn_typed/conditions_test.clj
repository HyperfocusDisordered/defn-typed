(ns defn-typed.conditions-test
  "A leading condition map `{:pre […] :post […]}` holds wherever the body lands: in
   <name>--positional (a plain defn's conditions) and in the function itself (a body whose recur
   targets the function, a table of more than 20 rows)."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.core :refer [defn-typed]]))

(defn-typed counted {:n :int} -> :int
  {:pre [(pos? n)]}
  n
)

(defn-typed looped {:n :int} -> :int
  {:pre [(pos? n)]}
  (if (< n 5) (recur {:n (inc n)}) n)
)

(defn-typed wide {
  :a :int :b :int :c :int :d :int :e :int :f :int :g :int
  :h :int :i :int :j :int :k :int :l :int :m :int :n :int
  :o :int :p :int :q :int :r :int :s :int :t :int :u :int
} -> :int
  {:post [(pos? %)]}
  (+ a b c d e f g h i j k l m n o p q r s t u)
)

(defn- assert-failure [f]
  (try (f) nil (catch AssertionError e (ex-message e))))

(deftest pre-and-post-hold-everywhere
  (testing "the body in <name>--positional: :pre holds"
    (is (= 3 (counted {:n 3})))
    (is (= "Assert failed: (pos? n)" (assert-failure #(counted {:n 0})))))
  (testing "a body whose recur targets the function: :pre holds, on every recur too"
    (is (= 5 (looped {:n 1})))
    (is (= "Assert failed: (pos? n)" (assert-failure #(looped {:n 0})))))
  (testing "more than 20 rows: :post holds"
    (let [row (zipmap [:a :b :c :d :e :f :g :h :i :j :k :l :m :n :o :p :q :r :s :t :u] (repeat 1))]
      (is (= 21 (wide row)))
      (is (= "Assert failed: (pos? %)" (assert-failure #(wide (assoc row :a -100)))))))
  (testing ":post with a recur to the function: a compile error, as in a plain fn (recur leaves the tail)"
    (is (re-find #"Can only recur from tail position"
                 (try (binding [*ns* (the-ns 'defn-typed.conditions-test)]
                        (eval '(defn-typed post-looped {:n :int} -> :int
                                 {:post [(pos? %)]}
                                 (if (< n 5) (recur {:n (inc n)}) n))))
                      nil
                      (catch Exception e (str (ex-message e) " " (some-> e ex-cause ex-message))))))))
