(ns defn-typed.forward-call-test
  "A literal call compiled before its callee's defn-typed definition (the callee declared above)
   compiles as a map call: the definition reports each such call under `:literal-check`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- load-source!
  "Writes lines (the source of namespace ns-name) to a temp file and loads it with
   defn-typed.literal-check = mode (nil: unset); {:path :err} or {:path :err :thrown <message>}.
   The namespace is removed."
  [ns-name lines mode]
  (let [file (java.io.File/createTempFile (str ns-name "-") ".clj")
        err (java.io.StringWriter.)
        previous (System/getProperty "defn-typed.literal-check")]
    (.deleteOnExit file)
    (spit file (str (str/join "\n" (cons (str "(ns " ns-name " (:require [defn-typed.core :refer [defn-typed defmeta]]))") lines)) "\n"))
    (try
      (if mode (System/setProperty "defn-typed.literal-check" (name mode)) (System/clearProperty "defn-typed.literal-check"))
      (binding [*err* err] (load-file (.getPath file)))
      {:path (.getPath file) :err (str err)}
      (catch Exception e
        {:path (.getPath file) :err (str err) :thrown (ex-message (last (take-while some? (iterate ex-cause e))))})
      (finally
        (if previous (System/setProperty "defn-typed.literal-check" previous) (System/clearProperty "defn-typed.literal-check"))
        (remove-ns ns-name)))))

(def source
  ;; line 1 = the ns form
  ["(declare later)"
   ""
   "(defn use-later [] (later {:x 1}))"
   ""
   "(defmeta later {:doc \"Its x.\"})"
   ""
   "(defn-typed later {:x :int} -> :int"
   "  x"
   ")"
   ""
   "(defn use-after [] (later {:x 2}))"])

(defn- line [path ns-name]
  (str "defn-typed: " path ":4 calls " ns-name "/later before its definition"
       " — the call compiles as a map, not positionally; move the definition above the call"))

(deftest forward-call-is-reported
  (testing ":warn (default): one line naming the caller's file:line; a call after the definition is not reported"
    (let [{:keys [path err thrown]} (load-source! 'forward.warn source nil)]
      (is (nil? thrown))
      (is (= [(line path 'forward.warn)] (str/split-lines err)))))
  (testing "without the defmeta: the same"
    (let [{:keys [path err]} (load-source! 'forward.bare (into (subvec source 0 4) (subvec source 6)) nil)]
      (is (= [(line path 'forward.bare)] (str/split-lines err)))))
  (testing ":error: the definition fails with that line"
    (let [{:keys [path thrown]} (load-source! 'forward.error source :error)]
      (is (= (line path 'forward.error) thrown))))
  (testing ":off: nothing"
    (is (= "" (:err (load-source! 'forward.off source :off))))))
