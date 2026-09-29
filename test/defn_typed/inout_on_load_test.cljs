(ns defn-typed.inout-on-load-test
  "cljs dev build: a defn-typed definition's defmeta cases run as its namespace loads (the fixture)."
  (:require [cljs.test :refer [deftest is testing]]
            [defn-typed.core :as core]
            [defn-typed.inout-on-load-fixture :as fixture]))

(defn- printed-lines
  "The captured lines of the fixture's definition `f-name`, the file:line locus cut off."
  [f-name]
  (into [] (keep #(second (re-find (re-pattern (str "^WARNING .*inout_on_load_fixture\\.cljs:\\d+ defn-typed\\.inout-on-load-fixture/" f-name " (.*)$")) %)))
        @fixture/printed))

(deftest cases-run-at-load
  (testing "one line per failing case at its defmeta's line (above and below the defn); a passing one, a case calling a declared function not yet defined, and :off print nothing"
    (is (= [["12" "defn-typed.inout-on-load-fixture/tripled" "1" "expected 5, got 6 — input {:n 2}"]
            ["30" "defn-typed.inout-on-load-fixture/below" "0" "expected 5, got 6 — input {:n 2}"]]
           (into [] (comp (map #(vec (rest (re-find #"^WARNING .*inout_on_load_fixture\.cljs:(\d+) (\S+) in/out case (\d+): (.*)$" %))))
                          (remove #(re-find #"/pick$" (second %))))
                 @fixture/printed)))))

(deftest mismatch-names-what-differs
  (is (= ["in/out case 0: [:sku] expected \"bmx\", got \"sd\" — input {:got {:sku \"sd\", :qty 1}}"
          "in/out case 1: [:qty] expected 1, missing — input {:got {:sku \"bmx\"}}"
          "in/out case 2: [:gift] unexpected false — input {:got {:sku \"bmx\", :gift false}}"
          "in/out case 3: [:address :city] expected \"Минск\", got \"Брест\" — input {:got {:address {:city \"Брест\"}}}"
          "in/out case 4: [:a] expected 1, got 2; [:b] expected 1, got 2 — input {:got {:b 2, :a 2}}"
          "in/out case 5: expected 5, got 6 — input {:got 6}"
          "in/out case 6: [:items] expected [1 2], got [1 3] — input {:got {:items [1 3]}}"]
         (printed-lines "pick"))))

(deftest error-mode-throws
  (is (= "f.cljs:12 defn-typed.inout-on-load-fixture/tripled in/out case 1: expected 5, got 6 — input {:n 2}"
         (try (core/inout-check! {:var #'fixture/tripled :mode :error :file "f.cljs" :line 12}) nil
              (catch :default e (ex-message e))))))
