# inout

A function's input/output contract and its examples, written next to the function.

- `defmeta` (above the function): the docstring and the `:inout-tests` — `[in out]` example pairs.
- `defn-typed` (the function): a map input `{key schema …}`, an `->` output schema, then the body.
  Every row key is a local in the body, defaults already filled.

The schemas are [malli](https://github.com/metosin/malli) schemas, stored as plain `:malli/schema`
var metadata. They check calls only where a dev/test/REPL loader runs malli's instrumentation;
a production build carries them as data and never loads malli. The example pairs run as tests
(`check-var`, `deftests!`) and from an editor's after-edit hook.

Works in Clojure and ClojureScript (`.clj`, `.cljs`, `.cljc`).

## Install

```clojure
;; deps.edn
{:deps {io.github.denisovchar/inout {:local/root "/path/to/inout"}}}
```

`metosin/malli` comes along as a dependency (see [malli versions](#malli-versions)).

## Example

Read top to bottom: the task, then its inputs and outputs, then the typed function.

<!-- readme-test -->
```clojure
(ns example (:require [inout.core :refer [defn-typed defmeta]]))

(defmeta fizzbuzz
  {:doc "FizzBuzz: 'Fizz' for multiples of 3, 'Buzz' for multiples of 5, 'FizzBuzz' for both, else the number as a string."
   :inout-tests [[{:n 1}  "1"]
                 [{:n 3}  "Fizz"]
                 [{:n 5}  "Buzz"]
                 [{:n 15} "FizzBuzz"]
                 [{:n 7}  "7"]]})

(defn-typed fizzbuzz {
  :n :int
} -> :string

  (cond (zero? (mod n 15)) "FizzBuzz"
        (zero? (mod n 3))  "Fizz"
        (zero? (mod n 5))  "Buzz"
        :else              (str n)))
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
      (second (re-matches #"invite-([A-Za-z0-9_-]+)" (or start-param "")))))
```

Both blocks run as a test (`test/inout/readme_test.clj` evaluates them verbatim).

## Grammar

```
(defmeta name {:doc "…" :inout-tests [[in out] …] …other var metadata})

(defn-typed name {
  key schema
  key [props schema]
  …
} -> out-schema

  body…)
```

- **Order**: `defmeta` first, one empty line, then `defn-typed`. `defmeta` declares `name`, so it
  may precede the definition.
- **Input** = one map literal, one row per line: `key schema`, or `key [props schema]` for a row
  with props (a value vector whose first element is a map). Keys are keywords; qualified keys bind
  by their name (`:x/b` → `b`).
- **Defaults** live only in the row props, `{:optional true :default v}`. A defaulted row must be
  `:optional` (instrumentation checks the call before the defaults are filled); a defaulted row
  without it is a compile error.
- **Output** = any malli schema after `->`, on the line of the closing `}`.
- **Body**: no argument vector — every row key is already a local.
- **Table props** go on the map as reader metadata; `^{:as sym}` binds the whole defaults-filled
  map (keys beyond the rows included — `[:map …]` is open) to `sym`:

  ```clojure
  (defn-typed with-total ^{:closed true :as row} {
    :price :int
    :qty   [{:optional true :default 1} :int]
  } -> [:map [:total :int]]

    (assoc row :total (* price qty)))
  ```

- **`:inout-tests`**: one `[in out]` pair per line, `in` = the function's single argument (the map;
  or the scalar of a one-argument plain `defn`). A case passes iff `(= out (f in))`. A non-pair
  throws naming the var.
- A docstring, an attr-map or an argument vector inside `defn-typed` is a compile error naming the
  function. Single arity only.

`defn-typed` expands to plain Clojure:

```clojure
(do (def name-props [:map [key schema] …])
    (defn name {:malli/schema [:=> [:cat name-props] out-schema] :doc …}
      [m]
      (let [{:keys [key …]} (inout.core/with-defaults name-props m)]
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
  nothing it emits loads malli. `test/inout/core_test.clj` `release-form` asserts this.

## clj-kondo

The hooks ship in `resources/clj-kondo.exports/io.github.denisovchar/inout/`. In the consuming
project:

```sh
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

It copies them to `.clj-kondo/imports/io.github.denisovchar/inout/`, which clj-kondo loads with no
further config (checked with clj-kondo v2026.01.19). The `defn-typed` hook lints the rows, the
arrow and the body as the `def` + `defn` above, with the row keys as locals, and reports the same
shape errors as the macro; the `defmeta` hook lints the map as code.

## Check API (`inout.core`)

| fn | returns / does |
|---|---|
| `(check-var #'f)` | `{:var sym :cases n :failures [{:i :in :expected :actual}]}`; a throwing case → `:actual [:thrown msg]` + `:thrown-data` |
| `(check-vars vars)` / `(check-ns 'ns)` | `check-var` over the vars with cases (`check-ns` is a macro; in cljs pass a quoted literal) |
| `(case-vars vars)` | the distinct vars with cases, sorted |
| `(registered-vars 'ns)` | the vars a `defmeta` / `tests` registered in `ns` |
| `(undefined-metas 'ns)` | names a `defmeta` declared but nothing defined (missing or misspelt function) |
| `(forget-ns! 'ns)` | drops every case of `ns` (registered and attr-map); call right before reloading `ns` |
| `(deftests! 'ns)` (clj) | defines one `clojure.test` test `<fn>-inout` per var of `ns` with cases |
| `(test-ns! 'ns)` / `(test-var! #'f)` (clj) | runs `clojure.test` tests + cases under one report, prints one summary line |
| `(malli-reasons (malli-fns) ex-data)` | `<key path> · <message> · got <value>` lines for a `:malli.core/invalid-input`/`-output` ex-data |
| `*trace-cases*` | when true, `check-var` prints `inout-case <ns/fn> <i> <args>` before each case |
| `(with-defaults schema m)` | `m` with the row defaults filled (nested `[:map …]` rows too) |
| `(tests #'f [[[args…] out] …])` | legacy: registers positional-args cases; `defmeta` is the form for one-argument functions |

## After-edit hook

An editor hook that runs after every save of a `.clj*` file can use the API against a live REPL:
`(forget-ns! 'ns)`, reload `ns`, re-arm instrumentation, then `(check-ns 'ns)` and
`(undefined-metas 'ns)`, printing one `OK <ns> · in/out N/N` line or only the failing cases, with
`malli-reasons` naming the failing keys of an instrumentation rejection and `*trace-cases*` naming
the case a blocked eval stopped in.

## Compared with `malli.experimental/defn`

- `mx/defn` annotates positional arguments Plumatic-style (`[x :- :int, y :- :int]`), supports
  multi-arity, and expands to a `defn` followed by `(malli.core/=> name schema)` — a runtime call
  into `malli.core` at load.
- `defn-typed` takes one map argument whose rows are the schema, binds every row as a local,
  fills defaults from the rows, and stores the schema as `:malli/schema` metadata only (no malli
  call in the expansion). The example pairs live beside it in `defmeta`. Single arity only.

## malli versions

The library code calls `malli.core/explain`, `malli.core/form` and `malli.error/error-message`
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
