(ns defn-typed.load-size-base-fixture
  "load_size_fixture.clj without its functions: what its ns form adds to `load()`."
  (:require [defn-typed.core :refer [defn-typed]]))

(comment
  (defn-typed unused {:request :map} -> :map request))
