(ns defn-typed.inout-on-load-test
  "cljs dev build: a defn-typed definition's defmeta cases run as its namespace loads (the fixture)."
  (:require [cljs.test :refer [deftest is testing]]
            [defn-typed.core :as core]
            [defn-typed.inout-on-load-fixture :as fixture]))

(deftest cases-run-at-load
  (testing "one line per failing case at its defmeta's line (above and below the defn); a passing one, a case calling a declared function not yet defined, and :off print nothing"
    (is (= [["12" "defn-typed.inout-on-load-fixture/tripled" "1" "{:n 2}" "5" "6"]
            ["30" "defn-typed.inout-on-load-fixture/below" "0" "{:n 2}" "5" "6"]]
           (mapv #(vec (rest (re-find #"^WARNING .*inout_on_load_fixture\.cljs:(\d+) (\S+) in/out case (\d+): (.*) → expected (\S+), got (\S+)$" %)))
                 @fixture/printed)))))

(deftest error-mode-throws
  (is (= "f.cljs:12 defn-typed.inout-on-load-fixture/tripled in/out case 1: {:n 2} → expected 5, got 6"
         (try (core/inout-check! {:var #'fixture/tripled :mode :error :file "f.cljs" :line 12}) nil
              (catch :default e (ex-message e))))))
