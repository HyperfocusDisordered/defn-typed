(ns defn-typed.typed-check-test
  "A defn-typed definition evaluated with Typed Clojure on the classpath (clojure -M:typed) is
   type-checked as it loads, per `:typed-check`: :warn prints each finding, :error fails the load,
   :off does nothing; a check over its deadline is skipped with one line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-typed-check
  "Runs f with the system property defn-typed.typed-check set to mode (nil: unset), restoring it."
  [mode f]
  (let [previous (System/getProperty "defn-typed.typed-check")]
    (try
      (if mode
        (System/setProperty "defn-typed.typed-check" (name mode))
        (System/clearProperty "defn-typed.typed-check"))
      (f)
      (finally
        (if previous
          (System/setProperty "defn-typed.typed-check" previous)
          (System/clearProperty "defn-typed.typed-check"))))))

(defn- load-fixture!
  "Loads the fixture from its file under mode; [stderr thrown]."
  [mode]
  (let [err (java.io.StringWriter.)
        thrown (binding [*err* err]
                 (try (with-typed-check mode #(require 'defn-typed.typed-check-fixture :reload)) nil
                      (catch Throwable e e)))]
    [(str err) thrown]))

(def warning
  #"(?m)^WARNING defn-typed defn_typed/typed_check_fixture\.clj:(\d+) output of shout: Type mismatch:")

(deftest warn-by-default
  (testing "the default :warn: shout's body mismatch is printed at load, labelled; helped (unannotated helper), quiet (:off) and fits print nothing"
    (let [[out thrown] (load-fixture! nil)]
      (is (nil? thrown))
      (is (re-find warning out) out)
      (is (<= 9 (parse-long (second (re-find warning out))) 11) "the line is inside shout's form")
      (is (= 1 (count (re-seq #"(?m)^WARNING defn-typed" out))) out)
      (is (not (str/includes? out "Unannotated var")) out)
      (is (not (str/includes? out "quiet")) out))))

(deftest error-fails-the-load
  (testing ":error: the load throws the finding"
    (let [[_ thrown] (load-fixture! :error)
          root (last (take-while some? (iterate ex-cause thrown)))]
      (is (some? thrown))
      (is (re-find #"^defn-typed defn_typed/typed_check_fixture\.clj:\d+ output of shout: Type mismatch:" (str (ex-message root)))))))

(deftest off-does-nothing
  (testing ":off: nothing printed, nothing thrown"
    (is (= ["" nil] (load-fixture! :off)))))

(deftest over-the-deadline
  (testing "a check running past 2 s: one line, the load goes on"
    (load-fixture! :off)
    (let [check-form! (requiring-resolve 'defn-typed.typed-clojure/check-form!)
          started (System/nanoTime)
          [out thrown] (with-redefs-fn {check-form! (fn [_] (Thread/sleep 4000) [])}
                         #(load-fixture! nil))
          ms (/ (- (System/nanoTime) started) 1e6)]
      (is (nil? thrown))
      (is (= (str/join (for [f '[shout helped fits]]
                         (str "defn-typed: Typed Clojure check of defn-typed.typed-check-fixture/" f " skipped (over 2 s)\n")))
             out))
      (is (< ms 8000) (str ms " ms: each check abandoned at its deadline")))))
