(ns defn-typed.typed-bugs
  "Typed Clojure fixture: defn-typed functions, calls that fit their schemas (ok-calls) and eight
   planted bugs (bug-*), each a type error the checker finds through the schemas alone. The test
   checks this file form by form (defn-typed.typed-clojure/check-form!); it is never run."
  (:require [defn-typed.core :refer [defn-typed defnt defmeta]]))

(defmeta label-of
  {:doc "A person's label."})

(defn-typed label-of {:name :string} -> :string
  (str "@" name)
)

(defmeta zip-of
  {:doc "The zip of a shipping address."})

(defn-typed zip-of {
  :address [:map [:zip :int] [:city :string]]
} -> :int
  (:zip address)
)

(defmeta summary-of
  {:doc "A lot's summary."})

(defn-typed summary-of {:id :int} -> [:map [:id :int] [:title :string]]
  {:id id :title (str "lot " id)}
)

(defmeta cover-of
  {:doc "A lot's cover photo: its own, else the first of its photos."})

(defn-typed cover-of {
  :lot_id      [:maybe :int]
  :cover_photo [:maybe :string]
  :photo_keys  [:vector :string]
} -> [:maybe :string]
  (or cover_photo (first photo_keys))
)

(defmeta tagged
  {:doc "The row with its label tagged: the whole map is a declared row."})

(defn-typed tagged {:row [:map [:label :string]]} -> [:map [:label :string]]
  (assoc row :tag (str "#" (:label row)))
)

(defmeta countdown
  {:doc "n counted down to zero: a recur to the function keeps the body in the var itself."})

(defn-typed countdown {:n :int} -> :int
  (if (pos? n) (recur {:n (dec n)}) n)
)

(defmeta cart-total
  {:doc "The cart's total: a line's :qty defaults to 1."})

(defn-typed cart-total {
  :items [:sequential [:map [:price [:int {:min 1}]] [:qty [:int {:min 1 :default 1}]]]]
} -> :int
  (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))
)

(defn ok-calls
  "Every call fits the schemas: the checker reports nothing here."
  []
  [(label-of {:name "a"})
   (zip-of {:address {:zip 10115 :city "Berlin"}})
   (inc (:id (summary-of {:id 9})))
   (cover-of {:lot_id 9 :cover_photo nil :photo_keys []})
   (let [id (:id (summary-of {:id 9}))]
     (cover-of {:lot_id id :cover_photo (label-of {:name "a"}) :photo_keys ["k"]}))
   (:label (tagged {:row {:label "a"}}))
   (inc (countdown {:n 3}))
   (inc (cart-total {:items [{:price 100}]}))
   (inc (cart-total {:items [{:price 100 :qty 2} {:price 5}]}))])

(defn bug-arg-type
  "A wrong arg type into a defn-typed fn from a non-literal value (:lot_id wants [:maybe :int])."
  []
  (let [id (label-of {:name "9"})]
    (cover-of {:lot_id id :cover_photo nil :photo_keys []})))

(defn bug-return-use
  "A defn-typed fn's :string return used as a number."
  []
  (inc (label-of {:name "a"})))

(defn bug-nested-field
  "A nested-map field of the wrong type (:zip wants :int)."
  []
  (zip-of {:address {:zip "10115" :city "Berlin"}}))

(defn bug-returned-field-arg
  "The wrong value reaches a defn-typed fn through a field of another one's returned map."
  []
  (let [m (summary-of {:id 9})]
    (cover-of {:lot_id (:title m) :cover_photo nil :photo_keys []})))

(defn bug-returned-field-use
  "A field of a defn-typed fn's returned map used as a number."
  []
  (inc (:title (summary-of {:id 9}))))

(defn bug-nested-built-apart
  "The nested map is built apart from the call, its :zip a :string."
  []
  (let [addr {:zip (label-of {:name "10115"}) :city "Berlin"}]
    (zip-of {:address addr})))

(defn bug-item-type
  "An item of a :sequential row with a wrong type (:price wants :int)."
  []
  (cart-total {:items [{:price "100"}]}))

(defmeta lot-count
  {:doc "How many lots a label holds."})

;; bug-body-return: the body returns the :string row where the signature promises :int
(defn-typed lot-count {:label :string} -> :int
  (str label)
)

(defn bug-wrong-typed-key
  "A literal call whose key holds the wrong type (:name wants :string)."
  []
  (label-of {:name 1}))

(defn bug-misspelled-key
  "A literal call with a misspelled key (:nmae for :name)."
  []
  (label-of {:nmae "a"}))

(defmeta name-length
  {:doc "A name's length, promised as a :string."})

;; bug-body-type: the body returns an integer where the signature promises :string
(defn-typed name-length {:name :string} -> :string
  (count name)
)

(defmeta bumped-name
  {:doc "A name bumped by one: inc on a :string row."})

;; bug-body-internal: a call inside the body gets the wrong type, not about the output
(defn-typed bumped-name {:name :string} -> :string
  (str (inc name))
)

(defmeta label-length
  {:doc "A label's length, promised as a :string: defined with defnt, the short name."})

;; bug-body-type through defnt: the body returns an integer where the signature promises :string
(defnt label-length {:label :string} -> :string
  (count label)
)

(defn bug-defnt-arg-type
  "A literal call of a defnt function whose key holds the wrong type (:label wants :string)."
  []
  (label-length {:label 1}))
