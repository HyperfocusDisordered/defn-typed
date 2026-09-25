(ns defn-typed.malli-in-prod
  "malli instrumentation, at run time, of the functions whose `defmeta` carries `:malli-in-prod`,
   in any build, production included. Require this namespace once at the app's entry point: it
   brings malli into the build and installs the checker; without it an opted-in function runs
   unchecked and prints one line saying so. The check never changes a call's result and never
   throws into the caller; a violation reaches the handler (`on-malli-violation!`) off the call
   path, at most one event per function and failing-path set per 60 s."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [defn-typed.core :as core]
            [malli.core :as m]
            [malli.error :as me])
  #?(:clj (:import (java.util.concurrent ExecutorService Executors ThreadFactory))))

(defonce ^:private handler (atom nil))

(defn on-malli-violation!
  "Registers handler, a fn of one event map {:fn :direction :value :errors :schema :stack :at
   :repeats}, for every `:malli-in-prod` function; nil unregisters (each event then prints one
   stderr line). Returns handler."
  [handler-fn]
  (reset! handler handler-fn)
  handler-fn)

(def ^:dynamic *random*
  "The draw a `:sample`d call is checked by: a fn of no args returning a number in [0, 1)."
  rand)

(def ^:dynamic *now-ms*
  "The clock of `:at` and the dedupe window: a fn of no args returning epoch milliseconds."
  #?(:clj #(System/currentTimeMillis) :cljs #(.now js/Date)))

(def window-ms
  "One event per function, direction and failing-path set per window; the later violations in it
   are counted into the next event's `:repeats`. It keeps a hot path failing on every call from
   flooding the handler."
  60000)

(def stack-depth
  "Frames kept in `:stack`: the calling code is within the first few, the rest is framework noise."
  16)

(defonce ^:private windows (atom {}))

(defn- window-open!
  "The `:repeats` of the event a violation keyed k at ms emits (the violations counted since k's
   last event), or nil when k's window is still open: the violation is counted instead."
  [k ms]
  (let [open? #(and % (< (- ms %) window-ms))
        [old] (swap-vals! windows (fn [w]
                                    (if (open? (get-in w [k :start]))
                                      (update-in w [k :repeats] inc)
                                      (assoc w k {:start ms :repeats 0}))))
        {:keys [start repeats]} (get old k)]
    (when-not (open? start)
      (or repeats 0))))

(defn- call-stack
  "The top frames of the calling thread, as text, the checker's own frames left out."
  []
  #?(:clj (into []
                (comp (map str)
                      (remove #(or (str/includes? % "java.lang.Thread.getStackTrace")
                                   (str/starts-with? % "defn_typed.core$")
                                   (str/starts-with? % "defn_typed.malli_in_prod$")))
                      (take stack-depth))
                (.getStackTrace (Thread/currentThread)))
     :cljs (into [] (comp (drop 1) (take stack-depth)) (str/split-lines (str (.-stack (js/Error.)))))))

#?(:clj
   (defonce ^:private executor
     (delay (Executors/newSingleThreadExecutor
             (reify ThreadFactory
               (newThread [_ runnable]
                 (doto (Thread. ^Runnable runnable "defn-typed-malli-in-prod")
                   (.setDaemon true))))))))

(defn- off-call-path!
  "Runs f later, outside the caller: clj on one background thread (with the caller's bindings, so
   a line printed there goes to the caller's *err*), cljs on a 0 ms timeout."
  [f]
  #?(:clj (.execute ^ExecutorService @executor ^Runnable (bound-fn* f))
     :cljs (js/setTimeout f 0)))

(defn redact
  "value with the keys in ks removed from every map inside it, at any depth."
  [ks value]
  (if (seq ks)
    (walk/postwalk #(if (map? %) (apply dissoc % ks) %) value)
    value))

(defn- event
  "The handler's event of a violation: the value and each error's value redacted, an error whose
   path passes through a redacted key without its value."
  [{:keys [checker direction value errors stack at repeats]}]
  (let [ks (:redact checker)]
    {:fn (:fn checker)
     :direction direction
     :value (redact ks value)
     :errors (mapv (fn [{:keys [in] :as error}]
                     (cond-> {:path in :message (me/error-message error)}
                       (not-any? #(contains? ks %) in) (assoc :value (redact ks (:value error)))))
                   errors)
     :schema (get-in checker [direction :form])
     :stack stack
     :at at
     :repeats repeats}))

(defn- summary
  "The stderr line of an event when no handler is registered: paths and messages, no values."
  [{:keys [direction errors repeats] :as e}]
  (str "defn-typed.malli-in-prod: " (:fn e) " " (name direction) " "
       (str/join "; " (for [{:keys [path message]} errors] (str (core/path-text path) " — " message)))
       (when (pos? repeats) (str " (" repeats " more since the last report)"))))

(defn- deliver!
  "Hands e to the handler; a throwing handler prints one stderr line per window."
  [e]
  (if-let [handler-fn @handler]
    (try (handler-fn e)
         (catch #?(:clj Throwable :cljs :default) t
           (when (window-open! [::handler-threw] (:at e))
             (core/print-err! (str "defn-typed.malli-in-prod: the violation handler threw on " (:fn e) ": "
                                   (ex-message t))))))
    (core/print-err! (summary e))))

(defn- violation!
  "On the call thread: the errors, the dedupe decision and, for an emitted event, the stack; the
   event itself is built and delivered off the call path."
  [checker direction value]
  (let [errors (:errors (@(get-in checker [direction :explain]) value))
        at (*now-ms*)
        repeats (window-open! [(:fn checker) direction (vec (distinct (map :in errors)))] at)]
    (when repeats
      (let [stack (call-stack)]
        (off-call-path! #(deliver! (event {:checker checker :direction direction :value value
                                           :errors errors :stack stack :at at :repeats repeats})))))))

(defn checker
  "The checker of a `:malli-in-prod` function from its slot's spec {:fn :input :output :opts}: the
   validators compiled once, the explainers on the first violation; `:check!` never throws."
  [{:keys [input output opts] :as spec}]
  (let [side (fn [schema] {:valid? (m/validator schema) :explain (delay (m/explainer schema)) :form (m/form schema)})
        {:keys [sample redact]} (when (map? opts) opts)
        info {:fn (:fn spec) :redact redact :input (side input) :output (side output)}
        valid-input? (get-in info [:input :valid?])
        valid-output? (get-in info [:output :valid?])]
    {:sample sample
     :sampled? #(< (*random*) sample)
     :check! (fn [direction value]
               (try
                 (when-not (if (= :input direction) (valid-input? value) (valid-output? value))
                   (violation! info direction value))
                 (catch #?(:clj Throwable :cljs :default) t
                   (when (window-open! [(:fn spec) ::check-threw] (*now-ms*))
                     (core/print-err! (str "defn-typed.malli-in-prod: the check of " (:fn spec) " threw: "
                                           (ex-message t))))))
               nil)}))

(reset! core/malli-checker-builder checker)
