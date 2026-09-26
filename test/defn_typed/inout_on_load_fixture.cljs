(ns defn-typed.inout-on-load-fixture
  "Definitions whose defmeta cases run as this namespace loads (a dev build): console.error is
   captured into `printed` around them for defn-typed.inout-on-load-test."
  (:require [defn-typed.core :refer [defn-typed defmeta]]))

(def printed (atom []))
(def console-error js/console.error)
(set! js/console.error (fn [& parts] (swap! printed conj (apply str parts))))

(declare doubled)

(defmeta tripled
  {:inout-tests [[{:n 1} 3]
                 [{:n 2} 5]]})

(defn-typed tripled {:n :int} -> :int
  (* 3 n)
)

(defmeta fine {:inout-tests [[{:n 1} 3]]})

(defn-typed fine {:n :int} -> :int
  (* 3 n)
)

(defn-typed below {:n :int} -> :int
  (* 3 n)
)

(defmeta below {:inout-tests [[{:n 2} 5]]})

(defmeta forward {:inout-tests [[{:n 2} 7]]})

(defn-typed forward {:n :int} -> :int
  (doubled n)
)

(defn doubled [n] (* 2 n))

(defmeta off {:inout-check :off :inout-tests [[{:n 2} 5]]})

(defn-typed off {:n :int} -> :int
  (* 3 n)
)

(set! js/console.error console-error)
