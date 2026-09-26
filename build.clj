(ns build
  "Release: `clojure -T:build jar` → target/defn-typed-<version>.jar;
  `clojure -T:build deploy` → Clojars (CLOJARS_USERNAME / CLOJARS_PASSWORD from env)."
  (:require [clojure.data.xml :as xml]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(def lib 'io.github.hyperfocusdisordered/defn-typed)
(def version "0.3.1")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def url "https://github.com/HyperfocusDisordered/defn-typed")

(defn- pom-data []
  [[:description "A function's input/output contract and its examples, written next to the function."]
   [:url url]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]])

(defn- provided-deps
  "The Maven deps of the `:typed` alias as `provided` pom dependencies. `defn-typed.typed-clojure`
  requires Typed Clojure, which the library does not depend on: cljdoc's analyzer puts provided
  deps on its classpath and loads the namespace; consumers do not get them transitively."
  [basis]
  (for [[lib {:keys [mvn/version]}] (get-in basis [:aliases :typed :extra-deps])
        :when version]
    (xml/sexp-as-element
      [::pom/dependency
       [::pom/groupId (namespace lib)]
       [::pom/artifactId (name lib)]
       [::pom/version version]
       [::pom/scope "provided"]])))

(defn- add-dependencies!
  "Appends `deps` to the <dependencies> of the pom at `pom-file` (write-pom has no scope)."
  [pom-file deps]
  (let [pom (xml/parse-str (slurp pom-file) :skip-whitespace true)]
    (spit pom-file
          (xml/indent-str
            (update pom :content
                    (partial map #(cond-> %
                                    (= ::pom/dependencies (:tag %)) (update :content concat deps))))))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :resource-dirs ["resources"]
                  ;; cljdoc reads the README and the sources at this commit
                  :scm {:url url
                        :connection "scm:git:https://github.com/HyperfocusDisordered/defn-typed.git"
                        :developerConnection "scm:git:ssh://git@github.com/HyperfocusDisordered/defn-typed.git"
                        :tag (b/git-process {:git-args "rev-parse HEAD"})}
                  :pom-data (pom-data)})
    (add-dependencies! (b/pom-path {:lib lib :class-dir class-dir}) (provided-deps basis))
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file})
    (println "built" jar-file)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
