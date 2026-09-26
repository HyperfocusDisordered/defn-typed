(ns defn-typed.malli-in-prod-test
  "`:malli-in-prod`: the opted-in functions are checked with defn-typed.malli-in-prod loaded and no
   instrumentation; the call's result never changes; violations reach the handler off the call
   path, redacted and deduped."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [defn-typed.core :as core :refer [defn-typed defmeta]]
            [defn-typed.malli-in-prod :as mp]))

(defn- drain!
  "Waits until the background thread has run everything submitted so far."
  []
  (.get (.submit ^java.util.concurrent.ExecutorService @@#'mp/executor ^Callable (fn [] nil))))

(defn- events-of
  "The events f's calls hand to a registered handler."
  [f]
  (let [seen (atom [])]
    (mp/on-malli-violation! #(swap! seen conj %))
    (try (f) (drain!) @seen
         (finally (mp/on-malli-violation! nil)))))

(defn- stderr-of
  "What f's calls print to stderr, the background thread's lines included."
  [f]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err] (f) (drain!))
    (str err)))

(defmeta order-total
  {:doc "price × qty"
   :malli-in-prod {:redact #{:phone :token}}})

(defn-typed order-total {
  :price [:int {:min 1}]
  :qty   [:int {:min 1 :default 1}]
  :who   [{:optional true} [:map [:name :string] [:phone :string] [:auth [:map [:token :string]]]]]
} -> :int
  (* price qty)
)

(deftest good-calls-pass-silently
  (let [m {:price 100 :qty 3}]
    (is (= "" (stderr-of #(is (= [] (events-of (fn [] (is (= 300 (order-total m))) (is (= 100 (order-total {:price 100}))))))))))))

(deftest a-bad-input-reaches-the-handler-redacted
  (let [bad {:price 0 :who {:name "n" :phone "+100" :auth {:token "secret"}}}
        [e & more] (events-of #(is (= 0 (order-total bad))))]
    (is (nil? more))
    (testing "the event map"
      (is (= `order-total (:fn e)))
      (is (= :input (:direction e)))
      (is (= {:price 0 :who {:name "n" :auth {}}} (:value e)) "redacted keys are gone at every depth")
      (is (= [{:path [:price] :message "should be at least 1" :value 0}] (:errors e)))
      (is (= order-total-props (:schema e)))
      (is (str/starts-with? (first (:stack e)) "defn_typed.malli_in_prod_test$order_total") (first (:stack e)))
      (is (every? string? (:stack e)))
      (is (int? (:at e)))
      (is (= 0 (:repeats e)))))
  (testing "an error whose path passes through a redacted key carries no :value"
    (let [[e] (let [bad {:price 1 :who {:name "n" :phone 5 :auth {:token "t"}}}]
                (events-of #(order-total bad)))]
      (is (= [{:path [:who :phone] :message "should be a string"}] (:errors e)))
      (is (= {:price 1 :who {:name "n" :auth {}}} (:value e))))))

(defmeta labelled {:malli-in-prod true})

(defn-typed labelled {:n :int} -> :string
  (if (neg? n) n (str n))
)

(deftest a-bad-output-reaches-the-handler
  (let [[e] (events-of #(is (= -1 (labelled {:n -1}))))]
    (is (= [:output -1 [{:path [] :message "should be a string" :value -1}] :string]
           ((juxt :direction :value :errors :schema) e)))))

(defmeta thrown-at {:malli-in-prod true})

(defn-typed thrown-at {
  :a [:int {:min 0}]
  :b [:int {:min 0 :default 0}]
} -> :int
  (+ a b)
)

(deftest a-throwing-handler-leaves-the-caller-alone
  (mp/on-malli-violation! (fn [_] (throw (ex-info "handler down" {}))))
  (try
    (let [clock (atom 5000000)
          [bad-a bad-b] [{:a -1} {:a 1 :b -3}]
          err (binding [mp/*now-ms* #(swap! clock inc)]
                (stderr-of #(do (is (= -1 (thrown-at bad-a)))
                                (is (= -2 (thrown-at bad-b))))))]
      (is (= ["defn-typed.malli-in-prod: the violation handler threw on defn-typed.malli-in-prod-test/thrown-at: handler down"]
             (str/split-lines err))
          "two events (two failing paths), both throw, 1 ms apart: one line per 60 s"))
    (finally (mp/on-malli-violation! nil))))

(defmeta flood {:malli-in-prod true})

(defn-typed flood {:n [:int {:min 0}]} -> :int
  n
)

(deftest a-flood-is-one-event-per-window
  (let [clock (atom 1000000)
        now #(deref clock)]
    (binding [mp/*now-ms* now]
      (let [first-window (events-of #(dotimes [i 1000]
                                       (swap! clock inc)
                                       (flood {:n (- -1 i)})))]
        (is (= 1 (count first-window)) "1000 bad calls in 1 s: one event")
        (is (= [0 {:n -1}] ((juxt :repeats :value) (first first-window))))
        (is (= 1000001 (:at (first first-window)))))
      (swap! clock + mp/window-ms)
      (let [[e & more] (let [bad {:n -5}] (events-of #(flood bad)))]
        (is (nil? more))
        (is (= 999 (:repeats e)) "the next window's event counts the 999 suppressed ones")))))

(def ^:private bad-n
  "A map every {:n [:int {:min 0}]} function rejects (not a literal at the call: no compile-time warning)."
  {:n -1})

(defmeta sampled {:malli-in-prod {:sample 0.01}})

(defn-typed sampled {:n [:int {:min 0}]} -> :int
  n
)

(deftest sample-checks-that-fraction-of-calls
  (let [draws #(let [r (java.util.Random. 42)] (fn [] (.nextDouble r)))
        expected (count (filter #(< % 0.01) (repeatedly 10000 (draws))))
        clock (atom 9000000)]
    (binding [mp/*now-ms* #(deref clock)]
      (let [[e] (binding [mp/*random* (draws)]
                  (events-of #(dotimes [_ 10000] (sampled bad-n))))
            _ (swap! clock + mp/window-ms)
            [next-e] (binding [mp/*random* (constantly 0.0)]
                       (events-of #(sampled bad-n)))
            checked (inc (:repeats next-e))]
        (is (= 0 (:repeats e)))
        (is (= expected checked) "every call draws once: the checked calls are the draws under 0.01")
        (is (<= 70 checked 130) (str checked " of 10000 checked"))
        (println "sample 0.01, seed 42:" checked "of 10000 calls checked")))
    (testing "a call the draw leaves out is not checked"
      (binding [mp/*random* (constantly 0.5)]
        (is (= [] (events-of #(sampled bad-n))))))))

(defmeta quiet {:malli-in-prod true})

(defn-typed quiet {:price [:int {:min 1}]} -> :int
  price
)

(deftest no-handler-prints-one-line-per-event
  (let [calls #(dotimes [_ 3] (quiet {:price (dec 1)}))]
    (is (= ["defn-typed.malli-in-prod: defn-typed.malli-in-prod-test/quiet input :price — should be at least 1"]
           (str/split-lines (stderr-of calls))))))

(defmeta unloaded {:malli-in-prod true})

(defn-typed unloaded {:n [:int {:min 0}]} -> :int
  n
)

(deftest not-loaded-runs-unchecked-with-one-line
  (let [builder @core/malli-checker-builder]
    (reset! core/malli-checker-builder nil)
    (try
      (is (= ["defn-typed: :malli-in-prod on defn-typed.malli-in-prod-test/unloaded but defn-typed.malli-in-prod is not loaded"]
             (str/split-lines (stderr-of #(is (= [-1 -2] [(unloaded bad-n) (unloaded (update bad-n :n dec))])))))
          "one line per function, the calls unaffected")
      (finally (reset! core/malli-checker-builder builder))))
  (testing "loaded later: the next call builds the checker"
    (is (= 1 (count (events-of #(unloaded (update bad-n :n - 2))))))))

(defmeta counted-down {:malli-in-prod true})

(defn-typed counted-down {:n :int :acc [:int {:default 0}]} -> :int
  (if (pos? n) (recur {:n (dec n) :acc (+ acc n)}) acc)
)

(deftest recur-and-the-switch
  (testing "a body that recurs to the function: it recurs with the map inside <name>--body"
    (is (= 6 (counted-down {:n 3}))))
  (testing "an opted-in function is never rewritten to the positional call, switch on or off"
    (with-redefs [core/inline-on? (constantly true)]
      (is (= ['.invoke `order-total '{:price 1}] (vec ((:inline (meta #'order-total)) '{:price 1}))))
      (is (= `(padded-probe--positional 1)
             ((:inline (meta (binding [*ns* (the-ns 'defn-typed.malli-in-prod-test)]
                               (eval '(do (defn-typed.core/defn-typed padded-probe {:a :int} -> :int a)
                                       (var padded-probe))))))
              '{:a 1}))
          "a function without it is"))))

(deftest defmeta-rejects-a-bad-malli-in-prod
  (doseq [[v message] [[{:sample 2} ":sample is a number 0 < x ≤ 1, got 2"]
                       [{:redact [:a]} ":redact is a set of keys, got [:a]"]
                       [{:every 1} "takes :sample and :redact, got (:every)"]
                       [:on "true or a map {:sample 0<x≤1 :redact #{key …}}, got :on"]]]
    (is (= (str "defmeta f: :malli-in-prod is " message)
           (try (pr-str (macroexpand-1 (list 'defn-typed.core/defmeta 'f {:malli-in-prod v})))
                (catch Exception e (ex-message (or (ex-cause e) e))))))))

(deftest the-expansion-holds-no-malli
  (binding [*ns* (the-ns 'defn-typed.malli-in-prod-test)]
    (let [expansion (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int} -> :int a))
          symbols (fn [form] (filterv #(and (symbol? %) (some-> (namespace %) (str/starts-with? "malli")))
                                      (tree-seq coll? seq form)))]
      (swap! @#'core/pending-meta assoc `f {:malli-in-prod true})
      (let [opted (macroexpand-1 '(defn-typed.core/defn-typed f {:a :int} -> :int a))]
        (is (= [] (symbols expansion) (symbols opted)) "the check goes through defn-typed.core, malli stays in defn-typed.malli-in-prod")
        (is (some #{'f--malli-in-prod} (tree-seq coll? seq opted)))))))
