(ns defn-typed.inout-on-load-test
  "A defn-typed function's defmeta cases run when its definition loads: `:inout-check :warn` prints
   one line per failing case, `:error` fails the load, `:off` runs nothing; defmeta above or below
   the defn; a case calling a function defined further down the file runs once that one is defined."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- load-source!
  "Writes lines (the source of namespace ns-name) to a temp file, loads it, removes the namespace
   (unless `:keep`), and returns {:path :err} (what the load printed to stderr), or {:path :thrown}
   with the message it failed with."
  [ns-name lines & [keep]]
  (let [file (java.io.File/createTempFile (str ns-name "-") ".clj")
        err (java.io.StringWriter.)]
    (.deleteOnExit file)
    (spit file (str (str/join "\n" (cons (str "(ns " ns-name " (:require [defn-typed.core :refer [defn-typed defmeta]]))") lines)) "\n"))
    (try
      (binding [*err* err] (load-file (.getPath file)))
      {:path (.getPath file) :err (str err)}
      (catch Exception e
        {:path (.getPath file) :err (str err)
         :thrown (ex-message (last (take-while some? (iterate ex-cause e))))})
      (finally (when-not keep (remove-ns ns-name))))))

(defn- with-property
  "Runs f with the system property defn-typed.inout-check set to mode (nil: unset), restoring it."
  [mode f]
  (let [previous (System/getProperty "defn-typed.inout-check")]
    (try
      (if mode
        (System/setProperty "defn-typed.inout-check" (name mode))
        (System/clearProperty "defn-typed.inout-check"))
      (f)
      (finally
        (if previous
          (System/setProperty "defn-typed.inout-check" previous)
          (System/clearProperty "defn-typed.inout-check"))))))

(def broken
  ;; line 1 = the ns form: the defmeta is on line 2
  ["(defmeta tripled"
   " {:inout-tests [[{:n 1} 3]"
   "                [{:n 2} 5]]})"
   ""
   "(defn-typed tripled {:n :int} -> :int"
   "  (* 3 n)"
   ")"])

(deftest broken-case-prints-its-line
  (testing ":warn (default): one stderr line per failing case, at the defmeta's line; the load goes on"
    (let [{:keys [path err thrown]} (with-property nil #(load-source! 'inout-load.broken broken))]
      (is (nil? thrown))
      (is (= [(str "WARNING " path ":2 inout-load.broken/tripled in/out case 1: {:n 2} → expected 5, got 6")]
             (str/split-lines err))))))

(deftest fixed-case-prints-nothing
  (let [fixed (assoc broken 2 "                [{:n 2} 6]]})")]
    (is (= {:err ""} (select-keys (with-property nil #(load-source! 'inout-load.fixed fixed)) [:err :thrown])))))

(deftest a-throwing-case-fails
  (let [{:keys [path err]} (load-source! 'inout-load.throws
                                          ["(defmeta halved {:inout-tests [[{:n 0} 0]]})"
                                           ""
                                           "(defn-typed halved {:n :int} -> :int"
                                           "  (quot 2 n)"
                                           ")"])]
    (is (= [(str "WARNING " path ":2 inout-load.throws/halved in/out case 0: {:n 0} → expected 0, threw Divide by zero")]
           (str/split-lines err)))))

(deftest error-fails-the-load
  (testing "the project setting :error: the load throws with the line's text"
    (let [{:keys [path err thrown]} (with-property :error #(load-source! 'inout-load.error broken))]
      (is (= "" err))
      (is (= (str path ":2 inout-load.error/tripled in/out case 1: {:n 2} → expected 5, got 6") thrown))))
  (testing "the defmeta's own :inout-check :error wins over the project's :warn"
    (let [own (assoc broken 1 " {:inout-check :error :inout-tests [[{:n 1} 3]")]
      (is (re-find #"in/out case 1: \{:n 2\} → expected 5, got 6$"
                   (str (:thrown (with-property nil #(load-source! 'inout-load.own own)))))))))

(deftest off-runs-nothing
  (testing ":off: the cases are not run (the body, which prints, never runs)"
    (let [noisy (assoc broken 5 "  (binding [*out* *err*] (println \"ran\")) (* 3 n)")]
      (is (= {:err ""} (select-keys (with-property :off #(load-source! 'inout-load.off noisy)) [:err :thrown]))))))

(deftest defmeta-after-the-defn
  (let [below ["(defn-typed tripled {:n :int} -> :int"
               "  (* 3 n)"
               ")"
               ""
               "(defmeta tripled"
               " {:inout-tests [[{:n 1} 3]"
               "                [{:n 2} 5]]})"]
        {:keys [path err]} (with-property nil #(load-source! 'inout-load.below below))]
    (is (= [(str "WARNING " path ":6 inout-load.below/tripled in/out case 1: {:n 2} → expected 5, got 6")]
           (str/split-lines err)))))

(deftest case-calling-a-function-defined-below
  (testing "f's case calls g, defined further down: nothing while f loads; once g is defined the broken case prints once, the correct one nothing"
    (let [source ["(declare g)"
                  ""
                  "(defmeta f"
                  " {:inout-tests [[{:n 1} 2]"
                  "                [{:n 2} 7]]})"
                  ""
                  "(defn-typed f {:n :int} -> :int"
                  "  (g n)"
                  ")"
                  ""
                  "(defn g [n] (* 2 n))"]
          {:keys [path err]} (load-source! 'inout-load.forward source)]
      (is (= [(str "WARNING " path ":4 inout-load.forward/f in/out case 1: {:n 2} → expected 7, got 4")]
             (str/split-lines err)))
      (testing "loading the file again prints the line once more, never twice"
        (is (= 1 (count (str/split-lines (:err (load-source! 'inout-load.forward source))))))))))

(deftest waiting-case-of-a-function-never-defined
  (testing "g declared, never defined: nothing prints; loading f again replaces its waiting watches; defining g then runs the case once"
    (let [source ["(declare g)"
                  ""
                  "(defmeta f {:inout-tests [[{:n 2} 7]]})"
                  ""
                  "(defn-typed f {:n :int} -> :int"
                  "  (g n)"
                  ")"]
          watches #(count (.getWatches ^clojure.lang.Var (find-var 'inout-load.never/g)))]
      (try
        (is (= "" (:err (load-source! 'inout-load.never source :keep))))
        (let [{:keys [path err]} (load-source! 'inout-load.never source :keep)]
          (is (= "" err))
          (is (= 1 (watches)))
          (is (= [(str "WARNING " path ":4 inout-load.never/f in/out case 0: {:n 2} → expected 7, got 4")]
                 (str/split-lines (let [e (java.io.StringWriter.)]
                                    (binding [*err* e *ns* (the-ns 'inout-load.never)]
                                      (eval '(defn g [n] (* 2 n))))
                                    (str e)))))
          (is (= 0 (watches))))
        (finally (remove-ns 'inout-load.never))))))
