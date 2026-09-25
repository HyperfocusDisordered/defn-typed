(ns karma.card.inout-test
  "Runs the in/out cases written next to their functions. A namespace that gains cases is
   listed here; each must report cases (a namespace with none would pass vacuously). Below them:
   the pair format and what `defnmalli` and `defmeta` expand to, in cljs."
  (:require [cljs.test :refer [deftest is testing]]
            [bikes.auction.inout :as inout :refer [defnmalli defmeta]]
            [malli.instrument :as mi]
            [karma.card.state]
            [karma.card.api]
            [karma.card.views.manager]
            [karma.card.views.lot-detail]
            [karma.card.views.listing]
            [karma.card.views.xray]
            [karma.card.views.chat-demo]))

(defn- summary [results]
  {:cases (reduce + 0 (map :cases results))
   :failures (vec (mapcat :failures results))})

(deftest karma-card-state-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.state))]
    (testing "karma.card.state"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest karma-card-api-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.api))]
    (testing "karma.card.api"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest karma-card-views-manager-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.views.manager))]
    (testing "karma.card.views.manager"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest karma-card-views-listing-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.views.listing))]
    (testing "karma.card.views.listing"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest karma-card-views-lot-detail-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.views.lot-detail))]
    (testing "karma.card.views.lot-detail"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest karma-card-views-xray-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.views.xray))]
    (testing "karma.card.views.xray"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest karma-card-views-chat-demo-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'karma.card.views.chat-demo))]
    (testing "karma.card.views.chat-demo"
      (is (pos? cases))
      (is (= [] failures)))))

(deftest inout-cases
  (let [{:keys [cases failures]} (summary (inout/check-ns 'bikes.auction.inout))]
    (testing "bikes.auction.inout"
      (is (pos? cases))
      (is (= [] failures)))))

(defmeta padded
  {:doc "a + b, b defaulting to 2."
   :inout-tests [[{:a 1}      3]
                 [{:a 1 :b 5} 6]]})

(defnmalli padded
  ^{:closed true}
  {:a :int
   :b [{:optional true :default 2} :int]} -> :int

  (+ a b))

(defn- incremented
  {:inout-tests [[[1] 2]
                 [[5] 6]]}
  [n]
  (inc n))

(deftest pairs-are-the-cases
  (testing "registered pairs and attr-map pairs read as [i args expected]"
    (is (= [[0 [{:a 1}] 3] [1 [{:a 1 :b 5}] 6]] (vec (inout/var-cases #'padded))))
    (is (= [[0 [1] 2] [1 [5] 6]] (vec (inout/var-cases #'incremented)))))
  (testing "check-var runs them"
    (is (= {:var `padded :cases 2 :failures []} (inout/check-var #'padded)))
    (is (= {:var `incremented :cases 2 :failures []} (inout/check-var #'incremented)))))

(deftest a-case-that-is-not-a-pair-throws
  (doseq [bad [[[[1] 2 3]] [[[1]]] [[1 2]] [nil] '([[1] 2])]]
    (let [message (try (inout/register-tests! #'incremented bad) nil
                       (catch :default e (ex-message e)))]
      (is (re-find #"incremented" (str message)) (pr-str bad))))
  (swap! inout/registry dissoc `incremented))

(deftest defnmalli-expansion
  (testing "the input map (its metadata = the table's own props) turns into [:map …], def'd as <name>-props and referenced from :malli/schema"
    (is (= [:map {:closed true} [:a :int] [:b {:optional true :default 2} :int]] padded-props))
    ;; cljs var metadata keeps the source form; malli's collect! evaluates it (see below)
    (is (= '[:=> [:cat padded-props] :int] (:malli/schema (meta #'padded)))))
  (testing "defaults come from the table"
    (is (= 3 (padded {:a 1})))
    (is (= 6 (padded {:a 1 :b 5})))))

(deftest defmeta-expansion
  (testing "defmeta above the defnmalli: :doc goes into the defn at compile time; the registry also keeps it beside the cases (where a plain defn below keeps it)"
    (is (= "a + b, b defaulting to 2." (:doc (meta #'padded))))
    (is (= {:doc "a + b, b defaulting to 2."} (:meta (get @inout/registry `padded))))
    (is (= {:var `padded :cases 2 :failures []} (inout/check-var #'padded)))))

(mi/collect! {:ns [karma.card.inout-test]})

(deftest defnmalli-schema-is-instrumented
  (mi/instrument! {:filters [(mi/-filter-ns 'karma.card.inout-test)]})
  (try
    (testing "a good call passes (a defaulted key may be absent: its row is :optional), an unknown key is rejected as :malli.core/invalid-input"
      (is (= 3 (padded {:a 1})))
      (let [data (try (padded {:a 1 :c 3}) nil
                      (catch :default e (ex-data e)))]
        (is (= :malli.core/invalid-input (:type data)))
        (is (= [{:a 1 :c 3}] (vec (:args (:data data)))))))
    (finally
      (mi/unstrument! {:filters [(mi/-filter-ns 'karma.card.inout-test)]}))))
