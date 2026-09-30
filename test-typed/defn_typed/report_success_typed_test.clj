(ns defn-typed.report-success-typed-test
  "`:report-success` with Typed Clojure on the classpath (clojure -M:typed): a load whose functions
   all type-check reads `types ok`; a Typed Clojure finding prints its WARNING line and no success
   line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.core]))

(defn- loaded
  "What loading lines (the source of namespace ns-name) from a temp file prints to stderr with the
   system property defn-typed.report-success true, the ended load reported right after it returns."
  [ns-name lines]
  (let [file (java.io.File/createTempFile (str ns-name "-") ".clj")
        err (java.io.StringWriter.)
        previous (System/getProperty "defn-typed.report-success")]
    (.deleteOnExit file)
    (spit file (str (str/join "\n" (cons (str "(ns " ns-name " (:require [defn-typed.core :refer [defn-typed defmeta]]))") lines)) "\n"))
    (try
      (System/setProperty "defn-typed.report-success" "true")
      (binding [*err* err] (load-file (.getPath file)))
      (some-> (resolve 'defn-typed.core/flush-ended-loads!) (apply []))
      (str err)
      (finally
        (if previous
          (System/setProperty "defn-typed.report-success" previous)
          (System/clearProperty "defn-typed.report-success"))
        (remove-ns ns-name)))))

(def typed
  ["(defmeta doubled {:inout-tests [[{:n 1} 2] [{:n 2} 4]]})"
   ""
   "(defn-typed doubled {:n :int} -> :int"
   "  (* 2 n)"
   ")"
   ""
   "(defn-typed label {:n :int} -> :string"
   "  (str n)"
   ")"])

(deftest every-function-type-checks
  (is (= "defn-typed ✓ report-success.typed: 2 fns · 2 in/out cases · types ok\n"
         (loaded 'report-success.typed typed))))

(deftest a-finding-prints-no-line
  (let [out (loaded 'report-success.mistyped (assoc typed 7 "  n"))]
    (is (re-find #"(?m)^WARNING defn-typed .* output of label: Type mismatch:" out) out)
    (is (not (str/includes? out "✓")) out)))

(deftest a-function-not-checked-reads-types-skipped
  (testing "a function whose defmeta says :typed-check :off: the others check clean, the line reads types skipped"
    (is (= "defn-typed ✓ report-success.partly: 2 fns · 2 in/out cases · types skipped\n"
           (loaded 'report-success.partly (into (subvec typed 0 6) ["(defmeta label {:typed-check :off})" "" "(defn-typed label {:n :int} -> :string" "  (str n)" ")"]))))))
