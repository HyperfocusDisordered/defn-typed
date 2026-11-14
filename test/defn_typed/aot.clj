(ns defn-typed.aot
  "Child JVMs of the tests: one compiles a namespace ahead of time in the release configuration, one
   loads a namespace in a fresh process; what a test reads is their stdout."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def classpath
  (->> (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))
       (map #(.getAbsolutePath (java.io.File. ^String %)))
       (str/join java.io.File/pathSeparator)))

(def classpath-without-tests
  "classpath minus the test directory, so a namespace the tests compiled loads from its classes."
  (let [tests (.getAbsolutePath (java.io.File. "test"))]
    (->> (str/split classpath (re-pattern java.io.File/pathSeparator))
         (remove #{tests})
         (str/join java.io.File/pathSeparator))))

(defn java-eval
  "stdout of a fresh JVM that evaluates form on cp in the release configuration (`:dev` off, the
   `:inline` switch on); throws with its stderr when it fails."
  [cp form]
  (let [{:keys [exit out err]} (shell/sh "java" "-Ddefn-typed.dev=false" "-Ddefn-typed.inline=true"
                                         "-cp" cp "clojure.main" "-e" form)]
    (when-not (zero? exit) (throw (ex-info err {:exit exit :form form})))
    out))

(defn with-compiled
  "Compiles the namespace ns-sym ahead of time into a fresh temp directory, calls (f dir) and removes
   the directory."
  [ns-sym f]
  (let [dir (java.nio.file.Files/createTempDirectory "defn-typed-aot" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir)]
    (try
      (java-eval (str path java.io.File/pathSeparator classpath)
                 (str "(binding [*compile-path* " (pr-str path) "] (compile '" ns-sym "))"))
      (f path)
      (finally (doseq [file (reverse (file-seq (.toFile dir)))] (.delete ^java.io.File file))))))
