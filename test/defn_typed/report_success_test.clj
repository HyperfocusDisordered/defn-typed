(ns defn-typed.report-success-test
  "`:report-success` (defn-typed.edn, here the system property): off (the default), a file load
   prints nothing more; on, a load whose defn-typed checks all passed prints one line per namespace
   once the load ends, and a load with a failing check prints none. Typed Clojure is not on this
   suite's classpath: the line reads `types skipped`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.core]))

(defn- with-report-success
  "Runs f with the system property defn-typed.report-success set to value (nil: unset), restoring it."
  [value f]
  (let [previous (System/getProperty "defn-typed.report-success")]
    (try
      (if value
        (System/setProperty "defn-typed.report-success" value)
        (System/clearProperty "defn-typed.report-success"))
      (f)
      (finally
        (if previous
          (System/setProperty "defn-typed.report-success" previous)
          (System/clearProperty "defn-typed.report-success"))))))

(defn- load-source!
  "Writes lines (the source of namespace ns-name) to a temp file and loads it with *err* bound to
   err; the namespace is removed after. Returns the file's path."
  [err ns-name lines]
  (let [file (java.io.File/createTempFile (str ns-name "-") ".clj")]
    (.deleteOnExit file)
    (spit file (str (str/join "\n" (cons (str "(ns " ns-name " (:require [defn-typed.core :refer [defn-typed defmeta]]))") lines)) "\n"))
    (try
      (binding [*err* err] (load-file (.getPath file)))
      (.getPath file)
      (finally (remove-ns ns-name)))))

(defn- report-ended-loads!
  "Reports the loads that ended now, as the reporting thread does on its next look
   (defn-typed.core/flush-ended-loads!)."
  []
  (some-> (resolve 'defn-typed.core/flush-ended-loads!) (apply [])))

(defn- loaded
  "What loading lines as namespace ns-name prints to stderr under the report-success value, the ended
   load reported right after it returns (flush-ended-loads!, what the reporting thread runs)."
  [value ns-name lines]
  (let [err (java.io.StringWriter.)]
    (with-report-success value #(do (load-source! err ns-name lines)
                                    (report-ended-loads!)))
    (str err)))

(def clean
  ["(defmeta doubled {:inout-tests [[{:n 1} 2] [{:n 2} 4]]})"
   ""
   "(defn-typed doubled {:n :int} -> :int"
   "  (* 2 n)"
   ")"
   ""
   "(defmeta halved {:inout-tests [[{:n 4} 2]]})"
   ""
   "(defn-typed halved {:n :int} -> :int"
   "  (quot n 2)"
   ")"
   ""
   "(defn-typed plain {:s :string} -> :int"
   "  (count s)"
   ")"])

(deftest off-by-default
  (testing "the property unset (the default false): nothing printed"
    (is (= "" (loaded nil 'report-success.off clean))))
  (testing "any value but true is off"
    (is (= "" (loaded "yes" 'report-success.yes clean)))))

(deftest on-prints-one-line
  (testing "on: one line for the namespace, counting its defn-typed functions and the cases run"
    (is (= "defn-typed ✓ report-success.on: 3 fns · 3 in/out cases · types skipped\n"
           (loaded "true" 'report-success.on clean)))))

(deftest a-failing-case-prints-no-line
  (testing "a case that fails: its WARNING line, no success line"
    (let [out (loaded "true" 'report-success.failing (assoc clean 0 "(defmeta doubled {:inout-tests [[{:n 1} 2] [{:n 2} 5]]})"))]
      (is (= 1 (count (str/split-lines out))) out)
      (is (re-find #"^WARNING .* report-success.failing/doubled in/out case 1: expected 5, got 4" out) out)
      (is (not (str/includes? out "✓")) out))))

(deftest a-literal-mismatch-prints-no-line
  (testing "a literal call that fails its compile-time check: its WARNING line, no success line"
    (let [out (loaded "true" 'report-success.literal (conj clean "" "(defn caller [] (doubled {:n 1 :m 2}))"))]
      (is (re-find #"^WARNING defn-typed .*: \(doubled …\) :m — unknown key\n$" out) out)
      (is (not (str/includes? out "✓")) out))))

(deftest a-compile-error-prints-no-line
  (testing "a defn-typed form the macro rejects fails the load: no success line for the functions before it"
    (let [err (java.io.StringWriter.)
          thrown (with-report-success "true"
                   #(try (load-source! err 'report-success.broken (conj clean "" "(defn-typed broken {:a :int} {:b :int} -> :int a)"))
                         nil
                         (catch Exception e e)
                         (finally (report-ended-loads!))))]
      (is (some? thrown))
      (is (= "" (str err))))))

(deftest each-load-reports-once
  (testing "the same namespace loaded twice: a line per load, the first reported when the second begins"
    (let [err (java.io.StringWriter.)]
      (with-report-success "true" #(do (load-source! err 'report-success.twice clean)
                                       (load-source! err 'report-success.twice clean)
                                       (report-ended-loads!)))
      (is (= (str/join (repeat 2 "defn-typed ✓ report-success.twice: 3 fns · 3 in/out cases · types skipped\n"))
             (str err))))))

(deftest the-reporting-thread-prints-it
  (testing "no flush by the caller: the reporting thread prints the line after the load ends"
    (let [err (java.io.StringWriter.)]
      (with-report-success "true" #(load-source! err 'report-success.by-itself clean))
      (is (= "defn-typed ✓ report-success.by-itself: 3 fns · 3 in/out cases · types skipped\n"
             (loop [waited 0]
               (let [out (str err)]
                 (if (or (seq out) (< 2000 waited))
                   out
                   (do (Thread/sleep 25) (recur (+ waited 25)))))))))))

(deftest outside-a-file-load-nothing
  (testing "a definition evaluated at a REPL (no file load on the stack): no report"
    (let [err (java.io.StringWriter.)]
      (with-report-success "true"
        #(binding [*err* err
                   *ns* (the-ns 'defn-typed.report-success-test)
                   *file* "defn_typed/report_success_test.clj"]
           (eval '(defn-typed.core/defn-typed evaluated {:n :int} -> :int n))
           (report-ended-loads!)))
      (is (= "" (str err)))
      (is (empty? (some-> (resolve 'defn-typed.core/load-reports) deref deref))))))
