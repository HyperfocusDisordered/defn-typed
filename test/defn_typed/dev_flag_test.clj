(ns defn-typed.dev-flag-test
  "`:dev` (the clj analogue of goog.DEBUG): off, a namespace load only defines the functions — no
   in/out cases, no defmeta registration, no runtime unknown-key check, no `:stale-callers`
   signature or schema watch; on, all of them. The test alias sets `-Ddefn-typed.dev=true`; each
   test sets the property around the load it judges."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.core :as core]))

(defn- with-dev
  "Runs f with the system property defn-typed.dev set to value (a string; nil: unset), restoring it."
  [value f]
  (let [previous (System/getProperty "defn-typed.dev")]
    (try
      (if value
        (System/setProperty "defn-typed.dev" value)
        (System/clearProperty "defn-typed.dev"))
      (f)
      (finally
        (if previous
          (System/setProperty "defn-typed.dev" previous)
          (System/clearProperty "defn-typed.dev"))))))

(def source
  ;; line 1 = the ns form; the body prints whenever it runs
  ["(def Qty :int)"
   ""
   "(defmeta tripled"
   " {:inout-tests [[{:n 1} 4]]})"
   ""
   "(defn-typed tripled {:n :int :q Qty} -> :int"
   "  (binding [*out* *err*] (println \"body ran\"))"
   "  (* 3 n)"
   ")"])

(defn- load-and-call!
  "Loads source as namespace ns-name with defn-typed.dev = dev, calls its function with a real map
   holding an unknown key, and returns {:load <stderr of the load> :call <stderr of the call>
   :registered? :watches <watches on its schema var> :signature?}; the namespace is removed."
  [ns-name dev]
  (let [file (java.io.File/createTempFile (str ns-name "-") ".clj")
        err (java.io.StringWriter.)
        call-err (java.io.StringWriter.)]
    (.deleteOnExit file)
    (spit file (str (str/join "\n" (cons (str "(ns " ns-name " (:require [defn-typed.core :refer [defn-typed defmeta]]))") source)) "\n"))
    (try
      (with-dev dev #(binding [*err* err] (load-file (.getPath file))))
      (let [f @(find-var (symbol (str ns-name) "tripled"))
            m {:n 1 :zz 2}]
        (binding [*err* call-err] (f m))
        {:load (str err)
         :call (str/replace (str call-err) "body ran\n" "")
         :registered? (contains? @core/registry (symbol (str ns-name) "tripled"))
         :watches (count (.getWatches ^clojure.lang.Var (find-var (symbol (str ns-name) "Qty"))))
         :signature? (contains? @@#'core/signatures (symbol (str ns-name) "tripled"))})
      (finally (remove-ns ns-name)))))

(deftest dev-off-loads-only-the-functions
  (testing "unset (the default) and false: the load runs no case, the call checks no key, nothing registered or watched"
    (doseq [[ns-name dev] [['dev-flag.unset nil] ['dev-flag.off "false"]]]
      (is (= {:load "" :call "" :registered? false :watches 0 :signature? false}
             (load-and-call! ns-name dev))
          (str "defn-typed.dev = " (pr-str dev))))))

(deftest dev-on-keeps-the-dev-behaviour
  (testing "true: the case runs at load (the body prints, the case fails), the unknown key is reported, the cases registered, the schema var watched"
    (let [{:keys [load call] :as result} (load-and-call! 'dev-flag.on "true")]
      (is (re-find #"body ran\nWARNING .*:4 dev-flag.on/tripled in/out case 0: expected 4, got 3 — input \{:n 1\}\n$" load))
      (is (= "defn-typed: dev-flag.on/tripled unknown key :zz — the keys are :n :q\n" call))
      (is (= {:registered? true :watches 1 :signature? true}
             (select-keys result [:registered? :watches :signature?]))))))
