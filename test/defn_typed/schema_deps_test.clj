(ns defn-typed.schema-deps-test
  "A defn-typed function whose row is typed by a schema var (`:order Order`) follows a change of
   that var: its namespace is reloaded from disk, so its signature carries Order's new value and
   its callers follow per `:stale-callers`. CI runs this namespace with the switch on and off."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defn-typed.order-schema]
            [defn-typed.order-total :as total]
            [defn-typed.order-caller :as caller]
            [defn-typed.order-same-ns :as same]))

(def inline?
  "Whether this JVM compiles literal calls to positional calls."
  (not= "false" (System/getProperty "defn-typed.inline")))

(defn- with-stale-callers
  "Runs f with the system property defn-typed.stale-callers set to mode (nil: unset), restoring it."
  [mode f]
  (let [previous (System/getProperty "defn-typed.stale-callers")]
    (try
      (if mode
        (System/setProperty "defn-typed.stale-callers" (name mode))
        (System/clearProperty "defn-typed.stale-callers"))
      (f)
      (finally
        (if previous
          (System/setProperty "defn-typed.stale-callers" previous)
          (System/clearProperty "defn-typed.stale-callers"))))))

(defn- stderr-of
  "What f prints to stderr."
  [f]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err] (f))
    (str err)))

(defn- in-ns-eval!
  "Evaluates form in the namespace ns-sym (as a REPL does, no file); returns what it printed to stderr."
  [ns-sym form]
  (stderr-of #(binding [*ns* (the-ns ns-sym)] (eval form))))

(defn- reload-all-from-disk!
  "Order, total, its caller and the same-namespace pair as on disk, each compiled against the other."
  []
  (stderr-of (fn []
               (doseq [ns-sym '[defn-typed.order-schema defn-typed.order-total defn-typed.order-caller
                                defn-typed.order-same-ns]]
                 (require ns-sym :reload)))))

(use-fixtures :each (fn [t] (reload-all-from-disk!) (t) (reload-all-from-disk!)))

(defn- watch-keys
  "The keys of the watches on the var v that defn-typed installed."
  [v]
  (filter #(and (vector? %) (= :defn-typed.core/schema (first %))) (keys (.getWatches ^clojure.lang.IRef v))))

(def reloaded-line
  "defn-typed: defn-typed.order-total/total changed its signature, reloaded callers: defn-typed.order-caller\n")

(deftest changed-default
  (testing "a default inside Order changed: total is reloaded, and the caller's literal call gets the new default"
    (is (= 3 (caller/call)))
    (let [loads @total/loads
          out (in-ns-eval! 'defn-typed.order-schema '(def Order [:map [:price :int] [:qty [:int {:default 2}]]]))]
      (is (= 6 (caller/call)))
      (is (= (inc loads) @total/loads))
      (is (= (if inline? reloaded-line "") out)))))

(deftest added-required-key
  (testing "a required key added to Order: the caller's reload shows the literal check's warning"
    (let [out (in-ns-eval! 'defn-typed.order-schema
                           '(def Order [:map [:price :int] [:qty [:int {:default 1}]] [:currency :string]]))]
      (is (= (if inline?
               (str "WARNING defn-typed defn_typed/order_caller.clj:6: (total …) :order :currency — missing required key\n"
                    reloaded-line)
               "")
             out)))))

(deftest same-value
  (testing "Order def'd again to an equal value: nothing reloaded, nothing printed"
    (let [loads @total/loads
          out (in-ns-eval! 'defn-typed.order-schema '(def Order [:map [:price :int] [:qty [:int {:default 1}]]]))]
      (is (= loads @total/loads))
      (is (= "" out)))))

(deftest warn-mode
  (testing ":stale-callers :warn: one line, nothing reloaded"
    (let [loads @total/loads
          out (with-stale-callers :warn
                #(in-ns-eval! 'defn-typed.order-schema '(def Order [:map [:price :int] [:qty [:int {:default 2}]]])))]
      (is (= loads @total/loads))
      (is (= 3 (caller/call)))
      (is (= (str "defn-typed: defn-typed.order-schema/Order changed; defn-typed.order-total/total and its callers"
                  " use the old one — reload them\n")
             out)))))

(deftest off-mode
  (testing ":stale-callers :off at the definition: no watch installed at all"
    (with-stale-callers :off #(stderr-of (fn [] (require 'defn-typed.order-total :reload))))
    (is (empty? (watch-keys #'defn-typed.order-schema/Order)))
    (let [loads @total/loads
          out (in-ns-eval! 'defn-typed.order-schema '(def Order [:map [:price :int] [:qty [:int {:default 2}]]]))]
      (is (= loads @total/loads))
      (is (= "" out)))))

(deftest one-watch-per-function
  (testing "each definition replaces the function's watch: one per schema var, whatever the reloads"
    (dotimes [_ 3] (stderr-of #(require 'defn-typed.order-total :reload)))
    (is (= [[:defn-typed.core/schema 'defn-typed.order-total/total]] (watch-keys #'defn-typed.order-schema/Order)))))

(deftest same-namespace
  (testing "a schema of the function's own namespace: the reload that redefines it does not reload again"
    (is (= 1 (same/qty-of {:line {}})))
    (let [loads @same/loads
          out (in-ns-eval! 'defn-typed.order-same-ns '(def Line [:map [:qty [:int {:default 5}]]]))]
      (testing "a REPL def of Line reloads the namespace from disk once (the disk is the truth)"
        (is (= (inc loads) @same/loads))
        (is (= 1 (same/qty-of {:line {}})))
        (is (= "" out))))))
