(ns defn-typed.typed-check-outcomes-test
  "`:typed-check-cache :on`: a definition whose check fingerprint (own form, own schema, the
   schemas of the functions it calls, the checker's and defn-typed's versions) has a kept outcome
   is not checked again: the outcome (no finding, the findings, skipped) is reported as the check
   reported it. Outcomes are kept in `.defn-typed/typed-check-outcomes.txt` (here a temp file).
   The `:typed` alias runs the other suites with the cache off."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [defn-typed.core]
            [defn-typed.typed-clojure]))

(def ^:private scratch 'defn-typed.outcomes-scratch)

(defn- writer-idle!
  "Waits until the outcomes writer has done every write queued so far."
  []
  (let [done (promise)]
    (send-via @@#'defn-typed.core/outcomes-executor @#'defn-typed.core/outcomes-writer
              (fn [written] (deliver done true) written))
    (deref done 5000 nil)))

(defn- with-cache
  "Runs (f outcomes-file) with the system property defn-typed.typed-check-cache = mode, the
   outcomes kept in a fresh temp file, the in-memory outcomes unread, and each check given 60 s (a loaded
   machine must not turn a check into a skip); restores all of it."
  [mode f]
  (let [previous (System/getProperty "defn-typed.typed-check-cache")
        dir (.toFile (java.nio.file.Files/createTempDirectory "outcomes" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir ".defn-typed" "typed-check-outcomes.txt")]
    (try
      (System/setProperty "defn-typed.typed-check-cache" (name mode))
      (reset! @#'defn-typed.core/typed-check-outcomes nil)
      (with-redefs [defn-typed.core/outcomes-file (constantly file)
                    defn-typed.core/typed-check-ms 60000]
        (try (f file)
             (finally (writer-idle!))))
      (finally
        (reset! @#'defn-typed.core/typed-check-outcomes nil)
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

(deftest findings-are-replayed
  (testing "a check with findings: the next definition prints the same lines without the checker; a changed form is checked"
    (with-cache :on
      (fn [_]
        (counting
         (fn [calls]
           (let [shout '(defn-typed shout {:n :int} -> :string n)
                 first-out (define! shout)
                 second-out (define! shout)]
             (is (re-find #"^WARNING defn-typed \S+ output of shout: Type mismatch:" first-out) first-out)
             (is (= 1 @calls) "the second definition replayed the outcome")
             (is (= first-out second-out))
             (define! '(defn-typed shout {:n :int} -> :string (inc n)))
             (is (= 2 @calls) "another body: checked")))))))
  (testing ":error: a replayed finding fails the load with the same ex-info"
    (with-cache :on
      (fn [_]
        (let [shout '(defn-typed shout {:n :int} -> :string n)
              thrown (fn [] (let [previous (System/getProperty "defn-typed.typed-check")]
                              (try (System/setProperty "defn-typed.typed-check" "error")
                                   (define! shout) nil
                                   (catch Throwable e (last (take-while some? (iterate ex-cause e))))
                                   (finally (if previous
                                              (System/setProperty "defn-typed.typed-check" previous)
                                              (System/clearProperty "defn-typed.typed-check"))))))]
          (counting
           (fn [calls]
             (let [live (thrown)
                   replayed (thrown)]
               (is (= 1 @calls))
               (is (= :defn-typed.core/typed-check (:type (ex-data replayed))))
               (is (= (ex-message live) (ex-message replayed)))
               (is (= (ex-data live) (ex-data replayed)))))))))))

(deftest skipped-is-replayed
  (testing "a check past its deadline: the next definition prints the same skip line, no checker"
    (with-cache :on
      (fn [_]
        (let [check-form! @#'defn-typed.typed-clojure/check-form!
              calls (atom 0)]
          (with-redefs [defn-typed.core/typed-check-ms 200
                        defn-typed.typed-clojure/check-form! (fn [m] (swap! calls inc) (Thread/sleep 1000) (check-form! m))]
            (let [slow '(defn-typed slow {:n :int} -> :int (inc n))
                  first-out (define! slow)
                  second-out (define! slow)]
              (is (= "defn-typed: Typed Clojure check of defn-typed.outcomes-scratch/slow skipped (over 0 s)\n" first-out))
              (is (= first-out second-out))
              (is (= 1 @calls)))))))))

(deftest outcomes-on-disk
  (testing "each outcome is written as `<fingerprint>\t<edn>`; a JVM that reads the file replays it"
    (with-cache :on
      (fn [file]
        (counting
         (fn [calls]
           (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))
           (let [shout '(defn-typed shout {:n :int} -> :string n)
                 shout-out (define! shout)]
             (writer-idle!)
             (let [lines (str/split-lines (slurp file))]
               (is (= 2 (count lines)) (pr-str lines))
               (is (every? #(re-matches #"[0-9a-f]{40}\t\[:findings \[.*\]\]" %) lines) (pr-str lines))
               (is (= lines (sort lines))))
             (reset! @#'defn-typed.core/typed-check-outcomes nil)
             (is (= "" (define! '(defn-typed kept {:n :int} -> :int (* 2 n)))))
             (is (= shout-out (define! shout)))
             (is (= 2 @calls) "read back from the file: both replayed")))))))
  (testing "an unreadable file: one line, then nothing kept"
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

(deftest moved-form-replays-current-lines
  (testing "a finding in the definition's file is kept relative to the form's line: the same form further down gets the line a check would give it"
    (let [kept (#'defn-typed.core/kept-outcome
                {:outcome [:findings [{:file "a/b.clj" :line 12 :column 3 :message "m" :kind :output :defn-typed? true}
                                      {:file "other.clj" :line 4 :column 1 :message "n" :kind nil :defn-typed? false}]]
                 :file "a/b.clj" :line 10})]
      (is (= [:findings [{:file :own :line-offset 2 :column 3 :message "m" :kind :output :defn-typed? true}
                         {:file "other.clj" :line 4 :column 1 :message "n" :kind nil :defn-typed? false}]]
             kept))
      (is (= [:findings [{:file "a/b.clj" :line 22 :column 3 :message "m" :kind :output :defn-typed? true}
                         {:file "other.clj" :line 4 :column 1 :message "n" :kind nil :defn-typed? false}]]
             (#'defn-typed.core/replayed-outcome {:outcome kept :file "a/b.clj" :line 20}))))))
