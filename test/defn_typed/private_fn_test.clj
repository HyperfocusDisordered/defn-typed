(ns defn-typed.private-fn-test
  "A private defn-typed function (`^:private` on its name, or `:private true` in its defmeta) is
   instrumented like a public one in dev, and the vars it expands to are private too."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.private-fn]
            [malli.instrument :as mi]))

(defn- failure
  "What calling v with a map whose :qty is a string throws: the ex-data's :type of an ex-info, else
   the exception's class."
  [v]
  (let [m {:qty "x"}]
    (try (v m) :no-throw
         (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))
         (catch Exception e (class e)))))

(deftest private-fns-are-instrumented
  (let [filters {:filters [(mi/-filter-ns 'defn-typed.private-fn)]}]
    (try
      (mi/collect! {:ns ['defn-typed.private-fn]})
      (mi/instrument! filters)
      (testing "instrumented: a bad real-map call of either private fn is rejected as :malli.core/invalid-input"
        (is (= :malli.core/invalid-input (failure #'defn-typed.private-fn/hidden)))
        (is (= :malli.core/invalid-input (failure #'defn-typed.private-fn/hidden-by-meta))))
      (finally (mi/unstrument! filters)))))

(deftest generated-vars-inherit-privacy
  (testing "<name>--positional and <name>-props are private when the function is"
    (doseq [f '[hidden hidden-by-meta]
            suffix ["" "--positional" "-props"]
            :let [v (find-var (symbol "defn-typed.private-fn" (str f suffix)))]]
      (is (true? (:private (meta v))) (str f suffix))))
  (testing "a public function's generated vars stay public"
    (is (nil? (:private (meta #'defn-typed.private-fn/shown--positional))))))
