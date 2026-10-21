(ns defn-typed.typed-check-greens-test
  "`:typed-check-cache :on`: a Typed Clojure check whose fingerprint (own form, own schema, the
   schemas of the functions it calls, the checker's and defn-typed's versions) was green before
   does not run again; greens are kept in `.defn-typed/typed-check-greens.txt` (here a temp file).
   The `:typed` alias runs the other suites with the cache off."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [defn-typed.core]
            [defn-typed.typed-clojure]))

(def ^:private scratch 'defn-typed.greens-scratch)

(defn- writer-idle!
  "Waits until the greens writer has done every write queued so far."
  []
  (let [done (promise)]
    (send-via @@#'defn-typed.core/greens-executor @#'defn-typed.core/greens-writer
              (fn [written] (deliver done true) written))
    (deref done 5000 nil)))

(defn- with-cache
  "Runs (f greens-file) with the system property defn-typed.typed-check-cache = mode, the greens
   kept in a fresh temp file, the in-memory greens unread, and each check given 60 s (a loaded
   machine must not turn a check into a skip); restores all of it."
  [mode f]
  (let [previous (System/getProperty "defn-typed.typed-check-cache")
        dir (.toFile (java.nio.file.Files/createTempDirectory "greens" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir ".defn-typed" "typed-check-greens.txt")]
    (try
      (System/setProperty "defn-typed.typed-check-cache" (name mode))
      (reset! @#'defn-typed.core/typed-check-greens nil)
      (with-redefs [defn-typed.core/greens-file (constantly file)
                    defn-typed.core/typed-check-ms 60000]
        (try (f file)
             (finally (writer-idle!))))
      (finally
        (reset! @#'defn-typed.core/typed-check-greens nil)
        (if previous
          (System/setProperty "defn-typed.typed-check-cache" previous)
          (System/clearProperty "defn-typed.typed-check-cache"))))))

(defn- counting
  "Runs (f calls) with check-form! counting its calls in the atom calls."
  [f]
  (let [check-form! @#'defn-typed.typed-clojure/check-form!
        calls (atom 0)]
    (with-redefs [defn-typed.typed-clojure/check-form! (fn [m] (swap! calls inc) (check-form! m))]
      (f calls))))

(defn- define!
  "Evaluates form in the scratch namespace; its stderr."
  [form]
  (let [err (java.io.StringWriter.)]
    (binding [*ns* (the-ns scratch)
              *err* err]
      (eval form))
    (str err)))

(use-fixtures :once
  (fn [run]
    (binding [*ns* (create-ns scratch)]
      (refer-clojure)
      (require '[defn-typed.core :refer [defn-typed defmeta]]))
    (run)))

(deftest green-once-then-skipped
  (testing "the same definition again in the same JVM: check-form! is not called"
    (with-cache :on
      (fn [_]
        (counting
         (fn [calls]
           (is (= "" (define! '(defn-typed fits {:n :int} -> :int (inc n)))))
           (is (= 1 @calls))
           (is (= "" (define! '(defn-typed fits {:n :int} -> :int (inc n)))))
           (is (= 1 @calls) "the second definition's check did not run")))))))

(deftest changed-form-runs
  (testing "another body: another fingerprint, the check runs"
    (with-cache :on
      (fn [_]
        (counting
         (fn [calls]
           (define! '(defn-typed fits {:n :int} -> :int (inc n)))
           (define! '(defn-typed fits {:n :int} -> :int (dec n)))
           (is (= 2 @calls))))))))

(deftest changed-callee-schema-runs-the-caller
  (testing "the caller unchanged, its callee redefined with another schema: the caller's check runs"
    (with-cache :on
      (fn [_]
        (counting
         (fn [calls]
           (define! '(defn-typed callee {:n :int} -> :int n))
           (define! '(defn-typed caller {:n :int} -> :int (callee {:n n})))
           (is (= 2 @calls))
           (define! '(defn-typed caller {:n :int} -> :int (callee {:n n})))
           (is (= 2 @calls) "unchanged: skipped")
           (define! '(defn-typed callee {:n :int} -> :string (str n)))
           (is (= 3 @calls))
           (let [out (define! '(defn-typed caller {:n :int} -> :int (callee {:n n})))]
             (is (= 4 @calls) "the callee's schema is part of the caller's fingerprint")
             (is (str/includes? out "WARNING defn-typed") out))))))))

(deftest findings-are-not-kept
  (testing "a check with a finding runs and prints again on the next definition"
    (with-cache :on
      (fn [_]
        (counting
         (fn [calls]
           (let [first-out (define! '(defn-typed shout {:n :int} -> :string n))
                 second-out (define! '(defn-typed shout {:n :int} -> :string n))]
             (is (= 2 @calls))
             (is (re-find #"^WARNING defn-typed \S+ output of shout: Type mismatch:" first-out) first-out)
             (is (re-find #"^WARNING defn-typed \S+ output of shout: Type mismatch:" second-out) second-out))))))))

(deftest greens-on-disk
  (testing "a green check's fingerprint is written to the file; a JVM that reads it skips the check"
    (with-cache :on
      (fn [file]
        (counting
         (fn [calls]
           (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))
           (writer-idle!)
           (let [lines (str/split-lines (slurp file))]
             (is (= 1 (count lines)) (pr-str lines))
             (is (re-matches #"[0-9a-f]{40}" (first lines))))
           (reset! @#'defn-typed.core/typed-check-greens nil)
           (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))
           (is (= 1 @calls) "read back from the file: skipped"))))))
  (testing "an unreadable file: one line, then an empty set"
    (with-cache :on
      (fn [file]
        (io/make-parents file)
        (spit file "not a fingerprint\n")
        (counting
         (fn [calls]
           (is (= "defn-typed: typed-check cache unreadable — rebuilding\n"
                  (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))))
           (is (= 1 @calls)))))))
  (testing ":off: no file read or written, every check runs"
    (with-cache :off
      (fn [file]
        (counting
         (fn [calls]
           (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))
           (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))
           (writer-idle!)
           (is (= 2 @calls))
           (is (not (.exists ^java.io.File file)))))))))

(deftest identity-printed-values-are-stable
  (testing "a row whose default is an object (printed #object[… 0x<hash> …]): a new instance of it keeps the fingerprint"
    (with-cache :on
      (fn [_]
        (counting
         (fn [calls]
           (define! '(defn-typed pooled {:n :int :pool [:any {:default (Object.)}]} -> :int n))
           (define! '(defn-typed pooled {:n :int :pool [:any {:default (Object.)}]} -> :int n))
           (is (= 1 @calls))))))))
