(ns defn-typed.load-size-test
  "What a defn-typed function costs its namespace's `load()` method: the JVM runs every top-level form of
   an ahead-of-time compiled namespace in that one method, whose code is at most 65535 bytes."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [defn-typed.aot :as aot]))

(defn- load-bytes
  "The last bytecode offset of load() of the namespace compiled into dir (javap)."
  [dir ns-sym]
  (let [lines (-> (shell/sh "javap" "-c" "-p" "-cp" dir (str (munge ns-sym) "__init")) :out str/split-lines)
        body (->> lines (drop-while #(not (re-find #"public static void load\(\)" %))) rest
                  (take-while #(not (re-find #"^  \S" %))))]
    (apply max (keep #(some-> (re-find #"^\s+(\d+): " %) second parse-long) body))))

(deftest a-typed-function-of-one-map-adds-at-most-110-bytes
  (let [bytes-of #(aot/with-compiled % (fn [dir] (load-bytes dir %)))
        per-function (/ (- (bytes-of 'defn-typed.load-size-fixture) (bytes-of 'defn-typed.load-size-base-fixture)) 20.0)]
    (is (<= per-function 110) (str "bytes per `(defn-typed f {:request :map} -> :map request)`: " per-function))))
