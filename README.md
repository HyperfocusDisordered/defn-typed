# defn-typed

[![test](https://github.com/HyperfocusDisordered/defn-typed/actions/workflows/test.yml/badge.svg)](https://github.com/HyperfocusDisordered/defn-typed/actions/workflows/test.yml)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.hyperfocusdisordered/defn-typed.svg)](https://clojars.org/io.github.hyperfocusdisordered/defn-typed)
[![cljdoc badge](https://cljdoc.org/badge/io.github.hyperfocusdisordered/defn-typed)](https://cljdoc.org/d/io.github.hyperfocusdisordered/defn-typed)

A function's input/output contract and its examples, written next to the function.

## Why

Most languages give you one familiar shape for a typed function: name, inputs with their types,
output type, body.

TypeScript

```typescript
function orderTotal({ price, qty = 1, discount = 0 }:
  { price: number; qty?: number; discount?: number }): number
```

Kotlin

```kotlin
fun orderTotal(price: Int, qty: Int = 1, discount: Int = 0): Int
```

Swift

```swift
func orderTotal(price: Int, qty: Int = 1, discount: Int = 0) -> Int
```

Python

```python
def order_total(price: int, qty: int = 1, discount: int = 0) -> int:
```

Clojure has no such form. «Is Clojure typed?» has no short answer: yes, sort of, but the popular
ways to get there have an API no human wants to read next to their code.
[malli](https://github.com/metosin/malli) already solves the hard part (schemas, validation,
readable errors, instrumentation), so this library only changes how you write it down. What the
function does, then example inputs and outputs, then the typed function: plain data, in that
order, nothing else.

```clojure
(defn-typed order-total {
  :price    [:int {:min 1}]
  :qty      [:int {:min 1 :default 1}]
  :discount [:int {:min 0 :max 100 :default 0}]
} -> :int
```

Same shape, and the signature also says what none of the four can: `discount` is 0 to 100.

## Forms

- `defmeta` (above the function): the docstring and the `:inout-tests` — `[in out]` example pairs.
- `defn-typed` (the function): a map input `{key schema …}`, an `->` output schema, then the body.
  Every row key is a local in the body, defaults already filled.

The schemas are [malli](https://github.com/metosin/malli) schemas, stored as plain `:malli/schema`
var metadata. They check calls only where a dev/test/REPL loader runs malli's instrumentation;
a production build carries them as data and never loads malli. The example pairs run as tests
(`check-var`, `check-ns`, `deftests!`).

Works in Clojure and ClojureScript (`.clj`, `.cljs`, `.cljc`).

## Install

```clojure
;; deps.edn, from Clojars
{:deps {io.github.hyperfocusdisordered/defn-typed {:mvn/version "0.1.2"}}}

;; deps.edn, from git
{:deps {io.github.hyperfocusdisordered/defn-typed {:git/tag "v0.1.2" :git/sha "175d07c"}}}

;; shadow-cljs.edn
{:dependencies [[io.github.hyperfocusdisordered/defn-typed "0.1.2"]]}
```

`metosin/malli` comes along as a dependency (see [malli versions](#malli-versions)).

## Example

Read top to bottom: the task, then its inputs and outputs, then the typed function.

<!-- readme-test -->
```clojure
(ns example (:require [defn-typed.core :refer [defn-typed defmeta]]))

(defmeta order-total
  {:doc "Order total: price × qty, minus a percent discount."
   :inout-tests [[{:price 100}                      100]
                 [{:price 100 :qty 3}               300]
                 [{:price 100 :qty 3 :discount 10}  270]]})

(defn-typed order-total {
  :price    [:int {:min 1}]
  :qty      [:int {:min 1 :default 1}]
  :discount [:int {:min 0 :max 100 :default 0}]
} -> :int
  (quot (* price qty (- 100 discount)) 100)
)
```

The smallest one, a single input:

<!-- readme-test -->
```clojure
(defmeta fizzbuzz
  {:doc "FizzBuzz: 'Fizz' for multiples of 3, 'Buzz' for multiples of 5, 'FizzBuzz' for both, else the number as a string."
   :inout-tests [[{:n 1}  "1"]
                 [{:n 3}  "Fizz"]
                 [{:n 5}  "Buzz"]
                 [{:n 15} "FizzBuzz"]
                 [{:n 7}  "7"]]})

(defn-typed fizzbuzz {:n :int} -> :string
  (cond (zero? (mod n 15)) "FizzBuzz"
        (zero? (mod n 3))  "Fizz"
        (zero? (mod n 5))  "Buzz"
        :else              (str n))
)
```

A real one, from the app this library was extracted from:

<!-- readme-test -->
```clojure
(defmeta invite-token-of
  {:doc "The invite token a link carries: ?invite=<token>, else the Telegram start parameter
         invite-<token> (base64url, the only shape startapp accepts). nil when neither."
   :inout-tests [[{:url-token "abc" :start-param nil} "abc"]
                 [{:url-token "abc" :start-param "invite-other"} "abc"]
                 [{:url-token nil :start-param "invite-Xy_9-z"} "Xy_9-z"]
                 [{:url-token "" :start-param "bid-1-2"} nil]
                 [{:url-token nil :start-param nil} nil]]})

(defn-typed invite-token-of {
  :url-token   [:maybe :string]
  :start-param [:maybe :string]
} -> [:maybe :string]
  (or (not-empty url-token)
      (second (re-matches #"invite-([A-Za-z0-9_-]+)" (or start-param ""))))
)
```

Both blocks run as a test (`test/defn_typed/readme_test.clj` evaluates them verbatim).

## Syntax

```
(defmeta name {:doc "…" :inout-tests [[in out] …] …other var metadata})

(defn-typed name {key schema …} -> out-schema body…)
```

- `defmeta` goes before the `defn-typed` of the same name: the macro reads it to put `:doc` on
  the function. `defmeta` declares `name`.
- **Input** = one map literal `{key schema …}`. Keys are keywords; qualified keys bind by their
  name (`:x/b` → `b`).
- **Defaults** = `:default` in the type's own props, `:qty [:int {:min 1 :default 1}]`; that row
  is optional by itself.
- **Row props** `key [props schema]` (a value vector whose first element is a map) = `:optional`
  without a default; `:default` there is a compile error.
- **Output** = any malli schema after `->`.
- **Body**: no argument vector — every row key is already a local.
- **Table props** go on the map as reader metadata; `^{:as sym}` binds the whole defaults-filled
  map (keys beyond the rows included — `[:map …]` is open) to `sym`:

  ```clojure
  (defn-typed with-total ^{:closed true :as row} {
    :price :int
    :qty   [:int {:default 1}]
  } -> [:map [:total :int]]
    (assoc row :total (* price qty))
  )
  ```

- **`:inout-tests`** = `[in out]` pairs, `in` = the function's single argument (the map; or the
  scalar of a one-argument plain `defn`). A case passes iff `(= out (f in))`. A non-pair throws
  naming the var.
- A docstring, an attr-map or an argument vector inside `defn-typed` is a compile error naming the
  function. Single arity only.

`defn-typed` expands to plain Clojure:

```clojure
(do (def name-props [:map [key schema] …])   ; a defaulted row: [key {:optional true} schema]
    (defn name {:malli/schema [:=> [:cat name-props] out-schema] :doc …}
      [m]
      (let [{:keys [key …]} (defn-typed.core/with-defaults name-props m)]
        body…)))
```

so clj-kondo (with the exported hooks), malli's `collect!`/`instrument!`, `:arglists` and any
tool that reads a `defn` see a `defn`. `<name>-props` keeps entry order up to 8 rows (a larger map
literal reads as a hash map; the order is cosmetic).

## Checks run in dev/test only

- **Clojure**: a loader namespace on the test/REPL classpath only (never on the server's) requires
  the app and runs `(malli.instrument/collect! {:ns …})` + `(malli.instrument/instrument!)`. A bad
  call then throws `:malli.core/invalid-input` / `:malli.core/invalid-output`. A plain
  `(require 'ns :reload)` redefines the vars un-instrumented; re-collect + instrument after it.
- **ClojureScript**: the app calls `malli.dev.cljs/start!` under a dev define; the release build
  aliases it away (`:build-options {:ns-aliases {malli.dev.cljs malli.dev.cljs-noop}}`).
  `defmeta`'s registrations (cases, `:meta`, `#'f`) sit under `goog.DEBUG`, so a release build
  drops them. The released bundle has no malli code and no cases.
- `defn-typed`'s expansion contains no malli symbol (`:malli/schema` is a keyword in the attr-map):
  nothing it emits loads malli. `test/defn_typed/core_test.clj` `release-form` asserts this.

## clj-kondo

The hooks ship in `resources/clj-kondo.exports/io.github.hyperfocusdisordered/defn-typed/`. In the consuming
project:

```sh
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

It copies them to `.clj-kondo/imports/io.github.hyperfocusdisordered/defn-typed/`, which clj-kondo loads with no
further config (checked with clj-kondo v2026.01.19). The `defn-typed` hook lints the rows, the
arrow and the body as the `def` + `defn` above, with the row keys as locals, and reports the same
shape errors as the macro; the `defmeta` hook lints the map as code.

## Running the examples

- `(check-var #'f)` → `{:var sym :cases n :failures [{:i :in :expected :actual}]}`; a throwing
  case → `:actual [:thrown msg]`.
- `(check-ns 'ns)` → `check-var` over every function of `ns` that has examples.
- `(deftests! 'ns)` (clj) → one `clojure.test` test `<fn>-inout` per such function, so
  `clojure -M:test` runs them with the rest of the suite.
- A function with several positional arguments registers its examples with
  `(tests #'f [[[args…] out] …])`; `defmeta` pairs take one argument.

## Compared with `malli.experimental/defn`

- `mx/defn` annotates positional arguments Plumatic-style (`[x :- :int, y :- :int]`), supports
  multi-arity, and expands to a `defn` followed by `(malli.core/=> name schema)` — a runtime call
  into `malli.core` at load.
- `defn-typed` takes one map argument whose rows are the schema, binds every row as a local,
  fills defaults from the rows, and stores the schema as `:malli/schema` metadata only (no malli
  call in the expansion). The example pairs live beside it in `defmeta`. Single arity only.

## malli versions

The library code calls `malli.core/explain` and `malli.error/error-message`
(resolved lazily by `malli-fns`), and relies on `:malli/schema` metadata being collected by
`malli.instrument/collect!` (clj) and on the `:malli.core/invalid-input` /
`:malli.core/invalid-output` / `:malli.core/missing-key` data. `deps.edn` declares **0.20.1**.
Tested on 0.11.0 and 0.20.1; the oldest release both test suites pass on is **0.11.0**:

- clj: needs 0.9.0 (changelog: "`::m/extra-key` error retains the error value"; the suite fails
  on 0.8.9);
- cljs: needs 0.11.0 (changelog: "Replace `goog/mixin` with `Object.assign`"; on 0.10.4 current
  ClojureScript instrumentation fails with `goog.mixin is not a function`).

A project that declares its own malli gets that version (tools.deps picks the top-level one).

## Tests

```sh
clojure -M:test                                     # clj
clojure -M:cljs compile test && node out/node-tests.js   # cljs (shadow-cljs :node-test)
clj-kondo --lint src test                            # uses the exported hooks
```

## Contributing / upstream

TBD.

## License

MIT — see `LICENSE`.
