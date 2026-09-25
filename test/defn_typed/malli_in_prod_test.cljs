(ns defn-typed.malli-in-prod-test
  "`:malli-in-prod` in cljs: violations reach the handler on a 0 ms timeout, redacted and deduped;
   the call's result never changes."
  (:require [cljs.test :refer [deftest is async]]
            [defn-typed.core :refer [defn-typed defmeta]]
            [defn-typed.malli-in-prod :as mp]))

(defn- after-timeouts
  "Calls (f events) once the handler's timeouts have run; events = what calls handed the handler."
  [calls f]
  (let [seen (atom [])]
    (mp/on-malli-violation! #(swap! seen conj %))
    (calls)
    (js/setTimeout #(do (mp/on-malli-violation! nil) (f @seen)) 20)))

(defmeta order-total
  {:malli-in-prod {:redact #{:phone}}})

(defn-typed order-total {
  :price [:int {:min 1}]
  :qty   [:int {:min 1 :default 1}]
  :who   [{:optional true} [:map [:name :string] [:phone :string]]]
} -> :int
  (* price qty)
)

(deftest a-bad-input-reaches-the-handler-redacted
  (async done
    (let [bad {:price 0 :who {:name "n" :phone "+100"}}
          good {:price 2 :qty 3}]
      (after-timeouts
       #(do (is (= 0 (order-total bad)))
            (is (= 6 (order-total good))))
       (fn [[e & more]]
         (is (nil? more))
         (is (= [`order-total :input {:price 0 :who {:name "n"}} [{:path [:price] :message "should be at least 1" :value 0}] 0]
                ((juxt :fn :direction :value :errors :repeats) e)))
         (is (seq (:stack e)))
         (is (number? (:at e)))
         (done))))))

(defmeta flood {:malli-in-prod true})

(defn-typed flood {:n [:int {:min 0}]} -> :int
  n
)

(deftest a-flood-is-one-event-per-window
  (async done
    (let [clock (atom 1000000)]
      (after-timeouts
       #(binding [mp/*now-ms* (fn [] @clock)]
          (dotimes [i 1000] (swap! clock inc) (flood {:n (- -1 i)}))
          (swap! clock + mp/window-ms)
          (flood {:n (- 5)}))
       (fn [events]
         (is (= [0 999] (map :repeats events)) "1000 bad calls in 1 s: one event; the next window's event counts the 999 others")
         (done))))))

(defmeta thrown-at {:malli-in-prod true})

(defn-typed thrown-at {:n [:int {:min 0}]} -> :int
  n
)

(deftest a-throwing-handler-is-caught
  (async done
    (let [lines (atom [])
          console-error (.-error js/console)]
      (set! (.-error js/console) #(swap! lines conj %))
      (mp/on-malli-violation! (fn [_] (throw (js/Error. "handler down"))))
      (is (= -1 (thrown-at {:n (- 1 2)})) "the call returns its result")
      (js/setTimeout #(do (set! (.-error js/console) console-error)
                          (mp/on-malli-violation! nil)
                          (is (= ["defn-typed.malli-in-prod: the violation handler threw on defn-typed.malli-in-prod-test/thrown-at: handler down"]
                                 @lines))
                          (done))
                     20))))
