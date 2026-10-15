(ns defn-typed.tagline-test
  "README's first paragraph under the title is the only hand-written description of the library;
  the pom `:description` in build.clj mirrors it (and so does the GitHub About, set by the release
  script). A drifted pom description fails here, before a release."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]))

(defn readme-tagline
  "The first non-empty line after `# defn-typed` that is neither a badge nor a heading."
  []
  (->> (string/split-lines (slurp "README.md"))
       (drop-while #(not= "# defn-typed" %))
       rest
       (map string/trim)
       (remove string/blank?)
       (remove #(string/starts-with? % "[!["))
       first))

(defn pom-description
  "The string of `[:description \"…\"]` in build.clj."
  []
  (second (re-find #"\[:description \"([^\"]+)\"\]" (slurp "build.clj"))))

(deftest pom-description-mirrors-the-readme-tagline
  (is (not (string/blank? (readme-tagline))))
  (is (= (readme-tagline) (pom-description))))
