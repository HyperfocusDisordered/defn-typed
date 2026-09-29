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
      (is (= [(str "WARNING " path ":2 inout-load.broken/tripled in/out case 1: expected 5, got 6 — input {:n 2}")]
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
    (is (= [(str "WARNING " path ":2 inout-load.throws/halved in/out case 0: threw Divide by zero (expected 0) — input {:n 0}")]
           (str/split-lines err)))))

(deftest error-fails-the-load
  (testing "the project setting :error: the load throws with the line's text"
    (let [{:keys [path err thrown]} (with-property :error #(load-source! 'inout-load.error broken))]
      (is (= "" err))
      (is (= (str path ":2 inout-load.error/tripled in/out case 1: expected 5, got 6 — input {:n 2}") thrown))))
  (testing "the defmeta's own :inout-check :error wins over the project's :warn"
    (let [own (assoc broken 1 " {:inout-check :error :inout-tests [[{:n 1} 3]")]
      (is (re-find #"in/out case 1: expected 5, got 6 — input \{:n 2\}$"
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
    (is (= [(str "WARNING " path ":6 inout-load.below/tripled in/out case 1: expected 5, got 6 — input {:n 2}")]
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
      (is (= [(str "WARNING " path ":4 inout-load.forward/f in/out case 1: expected 7, got 4 — input {:n 2}")]
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
          (is (= [(str "WARNING " path ":4 inout-load.never/f in/out case 0: expected 7, got 4 — input {:n 2}")]
                 (str/split-lines (let [e (java.io.StringWriter.)]
                                    (binding [*err* e *ns* (the-ns 'inout-load.never)]
                                      (eval '(defn g [n] (* 2 n))))
                                    (str e)))))
          (is (= 0 (watches))))
        (finally (remove-ns 'inout-load.never))))))

(deftest mismatch-names-what-differs
  (testing "one line: the key paths that differ first (sorted), the case input last"
    (let [source ["(defmeta pick"
                  " {:inout-tests [[{:got {:sku \"sd\" :qty 1}}             {:sku \"bmx\" :qty 1}]"
                  "                [{:got {:sku \"bmx\"}}                   {:sku \"bmx\" :qty 1}]"
                  "                [{:got {:sku \"bmx\" :gift false}}       {:sku \"bmx\"}]"
                  "                [{:got {:address {:city \"Брест\"}}}     {:address {:city \"Минск\"}}]"
                  "                [{:got {:b 2 :a 2}}                      {:a 1 :b 1}]"
                  "                [{:got 6}                                5]"
                  "                [{:got {:items [1 3]}}                   {:items [1 2]}]]})"
                  ""
                  "(defn-typed pick {:got :any} -> :any"
                  "  got"
                  ")"]
          {:keys [path err]} (with-property nil #(load-source! 'inout-load.diffs source))
          head (str "WARNING " path ":2 inout-load.diffs/pick in/out case ")]
      (is (= [(str head "0: [:sku] expected \"bmx\", got \"sd\" — input {:got {:sku \"sd\", :qty 1}}")
              (str head "1: [:qty] expected 1, missing — input {:got {:sku \"bmx\"}}")
              (str head "2: [:gift] unexpected false — input {:got {:sku \"bmx\", :gift false}}")
              (str head "3: [:address :city] expected \"Минск\", got \"Брест\" — input {:got {:address {:city \"Брест\"}}}")
              (str head "4: [:a] expected 1, got 2; [:b] expected 1, got 2 — input {:got {:b 2, :a 2}}")
              (str head "5: expected 5, got 6 — input {:got 6}")
              (str head "6: [:items] expected [1 2], got [1 3] — input {:got {:items [1 3]}}")]
             (str/split-lines err))))))

(deftest assert-cases-carries-the-warning-line
  (testing "a failing or throwing case's clojure.test message is the on-load warning's text (its locus = the var's); a passing case reports :pass without a message"
    (let [source ["(defmeta f"
                  " {:inout-tests [[{:n 2} 5]"
                  "                [{:n 0} 0]"
                  "                [{:n 3} 6]]})"
                  ""
                  "(defn-typed f {:n :int} -> :int"
                  "  (* 3 (quot 6 n))"
                  ")"]
          {:keys [path err]} (with-property nil #(load-source! 'inout-load.assert source :keep))
          reports (atom [])]
      (try
        (with-redefs [clojure.test/report #(swap! reports conj (select-keys % [:type :message]))]
          ((requiring-resolve 'defn-typed.core/assert-cases) (resolve 'inout-load.assert/f)))
        (let [no-locus #(str/replace-first % #"^\S+:\d+ " "")
              warned (mapv (comp no-locus #(str/replace-first % "WARNING " "")) (str/split-lines err))]
          (is (= ["inout-load.assert/f in/out case 0: expected 5, got 9 — input {:n 2}"
                  "inout-load.assert/f in/out case 1: threw Divide by zero (expected 0) — input {:n 0}"]
                 warned))
          (is (= [:fail :error :pass] (mapv :type @reports)))
          (is (= warned (mapv (comp no-locus :message) (take 2 @reports))))
          (is (= path (second (re-find #"^(\S+):\d+ " (:message (first @reports)))))))
        (finally (remove-ns 'inout-load.assert))))))
