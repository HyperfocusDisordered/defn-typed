(ns defn-typed.ex-type-test
  "Every ex-info defn-typed throws carries `:type`, one of a closed set named in the README: one
   assertion per throwing site (the Typed Clojure one lives in test-typed, typed-check-test)."
  (:require [clojure.test :refer [deftest is testing]]
            [defn-typed.core :as core]
            [malli.core]))

(defn- type-of
  "The :type of the innermost ex-info f throws (a compile error wraps the macro's), nil when none."
  [f]
  (try (f) nil
       (catch Throwable e
         (some #(:type (ex-data %)) (reverse (take-while some? (iterate ex-cause e)))))))

(defn- eval-in
  "Evaluates form in a fresh namespace that refers defn-typed and defmeta; the namespace removed."
  [form]
  (let [ns-sym (gensym "ex-type-")]
    (binding [*ns* (create-ns ns-sym)]
      (try (refer-clojure)
           (refer 'defn-typed.core :only '[defn-typed defmeta])
           (eval form)
           (finally (remove-ns ns-sym))))))

(defn- with-property
  "Runs f with the system property defn-typed.<k> set to value, restoring it."
  [k value f]
  (let [prop (str "defn-typed." k)
        previous (System/getProperty prop)]
    (try (System/setProperty prop value) (f)
         (finally (if previous (System/setProperty prop previous) (System/clearProperty prop))))))

(defn- settings-file [content]
  (let [file (java.io.File/createTempFile "defn-typed" ".edn")]
    (.deleteOnExit file)
    (spit file content)
    file))

(deftest every-ex-info-has-a-type
  (testing "::invalid-cases: legacy cases, defmeta cases"
    (is (= ::core/invalid-cases (type-of #(core/register-tests! #'type-of [:not-a-pair]))))
    (is (= ::core/invalid-cases (type-of #(core/single-arg-pairs 'f [[1]])))))
  (testing "::unknown-keys: :unknown-keys :error"
    (is (= ::core/unknown-keys (type-of #(core/report-unknown-keys! {:fn 'f :mode :error :keys [:a] :unknown-keys [:zz]})))))
  (testing "::setting: a defn-typed.edn that is not a map, holds an unknown key, a bad value; a bad property"
    (is (= ::core/setting (type-of #(core/settings-of-file (settings-file "[:error]")))))
    (is (= ::core/setting (type-of #(core/settings-of-file (settings-file "{:strict true}")))))
    (is (= ::core/setting (type-of #(core/settings-of-file (settings-file "{:literal-check :fail}")))))
    (is (= ::core/setting (type-of #(with-property "literal-check" "loud" (fn [] (@#'core/setting :literal-check)))))))
  (testing "::invalid-schema: a row schema malli cannot build"
    (is (= ::core/invalid-schema (type-of #(eval-in '(defn-typed f {:n :it} -> :any n))))))
  (testing "::literal-call: a failing literal call under :literal-check :error"
    (is (= ::core/literal-call (type-of #(with-property "literal-check" "error"
                                            (fn [] (eval-in '(do (defn-typed f {:n :int} -> :any n) (f {:zz 1})))))))))
  (testing "::forward-call: a literal call compiled before its callee's definition, :literal-check :error"
    (is (= ::core/forward-call
           (type-of #(let [file (java.io.File/createTempFile "ex_type_forward" ".clj")]
                       (.deleteOnExit file)
                       (spit file (str "(ns ex-type-forward (:require [defn-typed.core :refer [defn-typed]]))\n"
                                       "(declare later)\n(defn use-later [] (later {:x 1}))\n"
                                       "(defn-typed later {:x :int} -> :int x)\n"))
                       (try (with-property "literal-check" "error" (fn [] (load-file (.getPath file))))
                            (finally (remove-ns 'ex-type-forward))))))))
  (testing "::invalid-form: a malformed defn-typed (nothing, no name, a docstring) or defmeta"
    (is (= ::core/invalid-form (type-of #(eval-in '(defn-typed)))))
    (is (= ::core/invalid-form (type-of #(eval-in '(defn-typed f "doc" {:n :int} -> :any n)))))
    (is (= ::core/invalid-form (type-of #(eval-in '(defmeta f [:not-a-map]))))))
  (testing "::inout-failure: a failing in/out case under :inout-check :error"
    (is (= ::core/inout-failure
           (type-of #(eval-in '(do (defmeta f {:inout-check :error :inout-tests [[{:n 1} 2]]})
                                   (defn-typed f {:n :int} -> :int n))))))))
