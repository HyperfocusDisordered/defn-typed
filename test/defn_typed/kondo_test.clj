(ns defn-typed.kondo-test
  "defn-typed.kondo over test/kondo_fixture: the config it writes, written only on change, the
   types the loaded functions carry, metadata on an input map, malli's registry left alone."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.kondo :as kondo]
            [kondo-fixture.order]
            [kondo-fixture.shape]
            [malli.clj-kondo :as mc]
            [malli.core :as m]))

(def fixture-dir "test/kondo_fixture")

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "defn-typed-kondo" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest emits-the-fixture-config-once
  (let [out (str (io/file (temp-dir) "configs/malli/config.edn"))
        first-run (kondo/emit! {:src-dirs [fixture-dir] :out out})]
    (testing "the three fixture functions, text equal to the expected config"
      (is (= {:written? true :fns 3 :out out} first-run))
      (is (= (slurp (io/file fixture-dir "expected-config.edn")) (slurp out))))
    (testing "the same sources again: the file is left as it is"
      (is (= {:written? false :fns 3 :out out} (kondo/emit! {:src-dirs [fixture-dir] :out out}))))))

(deftest types-are-the-loaded-functions-types
  (testing "each clj fixture fn's emitted arity = malli.clj-kondo over the :malli/schema the macro gave the loaded var
            (a row typed by a def carrying :default is optional, as the macro marks it)"
    (let [config (edn/read-string (slurp (io/file fixture-dir "expected-config.edn")))]
      (doseq [v [#'kondo-fixture.order/order-total #'kondo-fixture.shape/moved]
              :let [{:keys [ns name]} (meta v)
                    [loaded] (mc/from {:ns (ns-name ns) :name name :schema (m/function-schema (:malli/schema (meta v)))})]]
        (is (= (select-keys loaded [:args :ret])
               (get-in config [:linters :type-mismatch :namespaces (ns-name ns) name :arities 1]))
            (str (symbol v)))))))

(deftest metadata-on-the-input-map-becomes-its-props
  (testing "^{:as row} (0.3.1 forms, a compile error since): typed from its rows, no throw"
    (let [dir (temp-dir)
          out (str (io/file dir "config.edn"))]
      (spit (io/file dir "row.clj")
            "(ns kondo-fixture.row (:require [defn-typed.core :refer [defn-typed]]))\n(defn-typed tagged ^{:as row} {:id :int} -> :map\n  row\n)\n")
      (is (= {:written? true :fns 1 :out out} (kondo/emit! {:src-dirs [(str dir)] :out out})))
      (is (= {:arities {1 {:args [{:op :keys :req {:id :int}}] :ret {:op :keys}}}}
             (get-in (edn/read-string (slurp out)) [:linters :type-mismatch :namespaces 'kondo-fixture.row 'tagged]))))))

(deftest malli-registry-untouched
  (let [before (m/function-schemas)]
    (kondo/emit! {:src-dirs [fixture-dir] :out (str (io/file (temp-dir) "config.edn"))})
    (is (= before (m/function-schemas)))))

(deftest cli-options
  (is (= {:src-dirs ["a" "b"] :out "o"} (kondo/cli-options ["--src-dir" "a" "--src-dir" "b" "--out" "o"])))
  (is (= {:help true} (kondo/cli-options ["--help"])))
  (is (= {:error "no --out"} (kondo/cli-options ["--src-dir" "a"])))
  (is (= {:error "no --src-dir"} (kondo/cli-options ["--out" "o"])))
  (is (= {:error "unknown flag: -o"} (kondo/cli-options ["-o" "x"])))
  (is (= {:error "--out takes a value"} (kondo/cli-options ["--src-dir" "a" "--out"]))))
