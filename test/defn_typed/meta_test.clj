(ns defn-typed.meta-test
  "The var metadata of a clj defn-typed definition: the keys and values 0.3.4 gave (pinned below),
   as the namespace loads from source and from classes compiled ahead of time, and a call written
   after the definition compiles to the positional call either way."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.aot :as aot]
            [defn-typed.core :as core]
            [defn-typed.meta-fixture :as fx]))

(def order '[plain by-var documented hidden guarded])

(def pinned
  "name → every key of the var's metadata but `:inline` (a fn, tested by what it returns) and `:ns`."
  (let [common {:column 1 :file "defn_typed/meta_fixture.clj" :inline-arities #{1}}]
    {'plain      (assoc common :name 'plain :line 7
                        :arglists '([{:keys [request]}])
                        :malli/schema [:=> [:cat [:map [:request :map]]] :map])
     'by-var     (assoc common :name 'by-var :line 11
                        :arglists '([{:keys [request path-params]}])
                        :malli/schema [:=> [:cat [:map [:request :map] [:path-params [:map [:id :int]]]]] :map])
     'documented (assoc common :name 'documented :line 20 :doc "Adds one."
                        :arglists '([{:keys [n]}])
                        :malli/schema [:=> [:cat [:map [:n {:optional true} [:int {:min 0 :default 0}]]]] :int])
     'hidden     (assoc common :name 'hidden :line 24 :private true
                        :arglists '([{:keys [n]}])
                        :malli/schema [:=> [:cat [:map [:n :int]]] [:maybe :int]])
     'guarded    (assoc common :name 'guarded :line 30 :doc "Checked in production." :malli-in-prod true
                        :arglists '([{:keys [n]}])
                        :malli/schema [:=> [:cat [:map [:n :int]]] :int])}))

(deftest loaded-from-source
  (doseq [sym order
          :let [v (ns-resolve 'defn-typed.meta-fixture sym)]]
    (testing (str sym)
      (is (= (pinned sym) (dissoc (meta v) :inline :ns)))
      (is (= (the-ns 'defn-typed.meta-fixture) (:ns (meta v))))
      (is (fn? (:inline (meta v)))))))

(deftest call-site-expander
  (with-redefs [core/inline-on? (constantly true)]
    (is (= `(fx/plain--positional {:a 1}) ((:inline (meta #'fx/plain)) '{:request {:a 1}})))
    (is (= ['.invoke `fx/guarded '{:n 1}] (vec ((:inline (meta #'fx/guarded)) '{:n 1})))
        "an opted-in function keeps the map call")))

(def ^:private probe
  "Run in a fresh JVM. The map fns are replaced, so a call compiled to the positional call still
   answers the body's result. Prints that result, the expander's answer, the metadata of each var."
  (str "(require 'defn-typed.meta-fixture)"
       "(doseq [v [#'defn-typed.meta-fixture/plain #'defn-typed.meta-fixture/documented]]"
       "  (alter-var-root v (constantly (fn [m] :map-call))))"
       "(prn (defn-typed.meta-fixture/caller))"
       "(prn ((:inline (meta #'defn-typed.meta-fixture/plain)) '{:request {}}))"
       "(doseq [s '" (pr-str order) "]"
       "  (prn (dissoc (meta (ns-resolve 'defn-typed.meta-fixture s)) :inline :ns)))"))

(defn- probe-reads
  "[caller-result expander-answer & metadata] printed by the probe on classpath cp."
  [cp]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. (aot/java-eval cp probe)))]
    (take-while #(not= ::eof %) (repeatedly #(edn/read {:eof ::eof} reader)))))

(defn- check-probe [reads]
  (let [[called expander & metas] reads]
    (is (= [{:a 1} 5] called) "the call after the definition is the positional call: the replaced var is not reached")
    (is (= '(defn-typed.meta-fixture/plain--positional {}) expander))
    (is (= (map pinned order) metas))))

(deftest loaded-in-a-fresh-process
  (testing "from source"
    (check-probe (probe-reads aot/classpath)))
  (testing "from classes compiled ahead of time"
    (aot/with-compiled 'defn-typed.meta-fixture
      #(check-probe (probe-reads (str % java.io.File/pathSeparator aot/classpath-without-tests))))))
