(ns defn-typed.typed-clojure-test
  "Typed Clojure over defn-typed code (clojure -M:typed): install! makes the checker know the
   fixture's defn-typed functions from their schemas alone, then check-form! checks the fixture
   file form by form — the eight planted bugs are reported and involve a defn-typed function,
   every other form (the defn-typed forms included) is clean."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.typed-bugs]
            [defn-typed.typed-clojure :refer [install! check-form!]]))

(def fixture "test-typed/defn_typed/typed_bugs.clj")

(defn- top-level-forms
  "The fixture's top-level forms after its ns form, read in its namespace with their :line."
  []
  (binding [*ns* (the-ns 'defn-typed.typed-bugs)]
    (with-open [r (clojure.lang.LineNumberingPushbackReader. (io/reader fixture))]
      (vec (rest (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} r))))))))

(def installed
  (delay (install! {:namespaces ['defn-typed.typed-bugs]})))

(def findings
  "{[head name] [finding …]} of every top-level form of the fixture."
  (delay
    @installed
    (into {} (for [form (top-level-forms)]
               [[(first form) (second form)]
                (mapv #(update % :message (comp first str/split-lines))
                      (check-form! {:ns 'defn-typed.typed-bugs :form form :file fixture}))]))))

(def planted
  "The first line of the one error each planted bug gets and its :kind: a call of a defn-typed
   function = `input of <f>: `, a defn-typed body against its output = `output of <f>: `, the
   checker's own text after it; any other error keeps the checker's text."
  {'[defn bug-arg-type]            ["input of cover-of: Function cover-of could not be applied to arguments:" :input]
   '[defn bug-return-use]          ["Function inc could not be applied to arguments:" nil]
   '[defn bug-nested-field]        ["input of zip-of: Function zip-of could not be applied to arguments:" :input]
   '[defn bug-returned-field-arg]  ["input of cover-of: Function cover-of could not be applied to arguments:" :input]
   '[defn bug-returned-field-use]  ["Function inc could not be applied to arguments:" nil]
   '[defn bug-nested-built-apart]  ["input of zip-of: Function zip-of could not be applied to arguments:" :input]
   '[defn bug-item-type]           ["input of cart-total: Function cart-total could not be applied to arguments:" :input]
   '[defn-typed lot-count]         ["output of lot-count: Type mismatch:" :output]
   '[defn bug-wrong-typed-key]     ["input of label-of: Function label-of could not be applied to arguments:" :input]
   '[defn bug-misspelled-key]      ["input of label-of: Function label-of could not be applied to arguments:" :input]
   '[defn-typed name-length]       ["output of name-length: Type mismatch:" :output]
   '[defn-typed bumped-name]       ["Function inc could not be applied to arguments:" nil]})

(deftest install-annotates-every-defn-typed-function
  ;; 2 defn-typed.core vars; per function its -props, plus --positional when the body is there
  ;; (countdown recurs to itself: its body stays in the var, which typed.malli's provider types)
  (is (= (+ 2 (* 2 9) 1) @installed)))

(deftest the-planted-bugs-are-reported
  (doseq [[form [message kind]] planted]
    (testing (str form)
      (is (= [[message kind true]]
             (mapv (juxt :message :kind :defn-typed?) (get @findings form))))
      (is (every? #(and (= fixture (:file %)) (pos-int? (:line %))) (get @findings form))))))

(deftest every-other-form-is-clean
  (testing "calls that fit, and the defn-typed / defmeta forms themselves (no «Internal Error No bounds»)"
    (is (= {} (into {} (remove (comp empty? val)) (apply dissoc @findings (keys planted)))))
    (is (< 20 (count @findings)))))
