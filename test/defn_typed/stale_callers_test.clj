(ns defn-typed.stale-callers-test
  "A defn-typed function redefined with another signature reloads, from their files, the namespaces
   whose literal calls compiled to its old positional call (the switch on); CI runs this namespace
   both ways, and with the switch off no call is positional, so nothing is reloaded or printed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defn-typed.stale-callee]
            [defn-typed.stale-caller :as caller]))

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

(defn- redefine!
  "Evaluates form (a defn-typed of pair) in the callee's namespace; returns what it printed to stderr."
  [form]
  (stderr-of #(binding [*ns* (the-ns 'defn-typed.stale-callee)] (eval form))))

(use-fixtures :each
  (fn [t]
    ;; pair as on disk, the caller compiled against it
    (with-stale-callers :off
      #(stderr-of (fn []
                    (require 'defn-typed.stale-callee :reload)
                    (require 'defn-typed.stale-caller :reload))))
    (t)))

(def reloaded-line
  "defn-typed: defn-typed.stale-callee/pair changed its signature, reloaded callers: defn-typed.stale-caller\n")

(deftest reordered-rows
  (testing "rows in another order: the caller passes each value to its row"
    (let [out (redefine! '(defn-typed pair {:b :int :a :int} -> :any [a b]))]
      (is (= [1 2] (caller/call)))
      (is (= (if inline? reloaded-line "") out)))))

(deftest added-defaulted-row
  (testing "a new defaulted row: the caller gets its default"
    (let [out (redefine! '(defn-typed pair {:a :int :b :int :c [:int {:default 0}]} -> :any [a b c]))]
      (is (= [1 2 0] (caller/call)))
      (is (= (if inline? reloaded-line "") out)))))

(deftest removed-row
  (testing "a row the caller passes is gone: the reloaded caller shows the literal check's warning"
    (let [out (redefine! '(defn-typed pair {:a :int} -> :any [a]))]
      (is (= (if inline?
               (str "WARNING defn-typed defn_typed/stale_caller.clj:9: (pair …) :b — unknown key\n" reloaded-line)
               "")
             out)))))

(deftest literal-check-error
  (testing "a caller whose reload throws (:literal-check :error): its namespace and the message, the new definition in place"
    (let [out (stderr-of #(binding [*ns* (the-ns 'defn-typed.stale-callee)]
                            (System/setProperty "defn-typed.literal-check" "error")
                            (try
                              (eval '(defn-typed pair {:a :int} -> :any [a]))
                              (finally (System/clearProperty "defn-typed.literal-check")))))
          ;; through a local: a literal call here would make this namespace a caller of pair
          pair defn-typed.stale-callee/pair]
      (is (= [1] (pair {:a 1})))
      (if inline?
        (is (re-matches #"defn-typed: reloading defn-typed\.stale-caller failed: .*defn-typed defn_typed/stale_caller\.clj:9: \(pair …\) :b — unknown key\n"
                        out))
        (is (= "" out))))))

(deftest body-only-change
  (testing "the same rows, another body: nothing reloaded, nothing printed"
    (let [loads @caller/loads
          out (redefine! '(defn-typed pair {:a :int :b :int} -> :any {:a a :b b}))]
      (is (= {:a 1 :b 2} (caller/call)))
      (is (= loads @caller/loads))
      (is (= "" out)))))

(deftest warn-mode
  (testing ":stale-callers :warn prints the callers and reloads none"
    (let [loads @caller/loads
          out (with-stale-callers :warn #(redefine! '(defn-typed pair {:b :int :a :int} -> :any [a b])))]
      (is (= loads @caller/loads))
      (is (= (if inline?
               (str "defn-typed: defn-typed.stale-callee/pair changed its signature; callers compiled with the old one:"
                    " defn-typed.stale-caller — reload them\n")
               "")
             out)))))

(deftest off-mode
  (testing ":stale-callers :off: nothing reloaded, nothing printed"
    (let [loads @caller/loads
          out (with-stale-callers :off #(redefine! '(defn-typed pair {:b :int :a :int} -> :any [a b])))]
      (is (= loads @caller/loads))
      (is (= "" out)))))

(deftest caller-being-loaded
  (testing "a caller loading right now (its require :reload-all reloads the callee) compiles anew: not reloaded again"
    (with-stale-callers :off #(redefine! '(defn-typed pair {:b :int :a :int} -> :any [a b])))
    (let [loads @caller/loads
          out (with-bindings {#'clojure.core/*pending-paths* (list "/defn_typed/stale_caller")}
                (redefine! '(defn-typed pair {:a :int :b :int} -> :any [a b])))]
      (is (= loads @caller/loads))
      (is (= "" out)))))

(deftest registry
  (testing "a positional call records its caller's namespace; the switch off records none"
    (is (= (if inline? #{'defn-typed.stale-caller} nil)
           (get (some-> (resolve 'defn-typed.core/inlined-callers) deref deref) 'defn-typed.stale-callee/pair)))))
