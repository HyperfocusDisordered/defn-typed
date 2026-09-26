# defn-typed

[![test](https://github.com/HyperfocusDisordered/defn-typed/actions/workflows/test.yml/badge.svg)](https://github.com/HyperfocusDisordered/defn-typed/actions/workflows/test.yml)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.hyperfocusdisordered/defn-typed.svg)](https://clojars.org/io.github.hyperfocusdisordered/defn-typed)
[![cljdoc badge](https://cljdoc.org/badge/io.github.hyperfocusdisordered/defn-typed)](https://cljdoc.org/d/io.github.hyperfocusdisordered/defn-typed)

Typed functions for Clojure: one signature gives you static checks, runtime contracts,
compile-time literal checks and example tests, and a call costs what a positional call costs.

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
ways to get there have an API no human wants to read next to their code. defn-typed gives you that
shape, and builds everything else from the one signature:

- **static checks**: clj-kondo flags wrong keys and types as you type (see Static checking);
- **compile-time literal checks**: a literal call with a missing key or an out-of-range value warns
  during the build (see Compile-time literal checks);
- **runtime contracts**: every call is checked in the REPL and in tests;
- **example tests**: the `[in out]` pairs above the function run as tests;
- **zero-cost calls**: in release builds a literal-map call compiles to a positional call, as fast
  as a plain `defn` (see Zero-cost calls).

The schemas are [malli](https://github.com/metosin/malli) schemas: malli does the validation, the
error messages and the instrumentation. What the function does, then example inputs and outputs,
then the typed function: plain data, in that order, nothing else.

```clojure
(defn-typed order-total {
  :price    [:int {:min 1}]
  :qty      [:int {:min 1 :default 1}]
  :discount [:int {:min 0 :max 100 :default 0}]
} -> :int
```

Same shape, and the signature also says what none of the four can: `discount` is 0 to 100. The
range is checked at runtime, on every call, only while malli instrumentation is on: in the REPL
after `(malli.dev/start!)`, in tests after `(malli.instrument/instrument!)`. Without instrumentation
nothing is checked at run time, and cljs release builds contain no malli at all (unless you opt
functions in, see malli in production). Literal calls such
as `(order-total {:discount 150})` are also checked at compile time — see Compile-time literal
checks. clj-kondo checks keys and types (not the range) as you type, once the types are emitted —
see Static checking.

## Forms

- `defmeta` (above the function): the docstring and the `:inout-tests` — `[in out]` example pairs.
- `defn-typed` (the function): a map input `{key schema …}`, an `->` output schema, then the body.
  Every row key is a local in the body, defaults already filled.

The schemas are [malli](https://github.com/metosin/malli) schemas, stored as plain `:malli/schema`
var metadata. They check calls only where a dev/test/REPL loader runs malli's instrumentation;
a production build carries them as data and never loads malli (unless functions opt in, see
[malli in production](#malli-in-production)). The example pairs run as tests
(`check-var`, `check-ns`, `deftests!`).

Works in Clojure and ClojureScript (`.clj`, `.cljs`, `.cljc`).

## Install

```clojure
;; deps.edn, from Clojars
{:deps {io.github.hyperfocusdisordered/defn-typed {:mvn/version "0.3.0"}}}

;; deps.edn, from git
{:deps {io.github.hyperfocusdisordered/defn-typed {:git/tag "v0.3.0" :git/sha "1407d0e"}}}

;; shadow-cljs.edn
{:dependencies [[io.github.hyperfocusdisordered/defn-typed "0.3.0"]]}
```

`metosin/malli` comes along as a dependency (see [malli versions](#malli-versions)).

## Static checking

clj-kondo reports wrong calls of `defn-typed` functions once malli has written the functions'
types into the project's `.clj-kondo` directory. In the consuming project:

```sh
# 1. the defn-typed hooks (once, and again after upgrading the library)
mkdir -p .clj-kondo   # clj-kondo copies configs only into an existing config dir
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint

# 2. the types: load your namespaces, collect their schemas, emit (again after a schema change)
clojure -M -e "(require 'my.app.core 'malli.instrument 'malli.clj-kondo) (do (malli.instrument/collect! {:ns (all-ns)}) (malli.clj-kondo/emit!))"

# 3. lint as usual
clj-kondo --lint src
```

Step 2 writes `.clj-kondo/imports/metosin/malli-types-clj/config.edn`, which clj-kondo loads with
no further config; it covers functions defined in `.clj` and `.cljc` files. With the hooks alone
clj-kondo checks the form's shape and the body, not the types of the calls.

In a REPL, `(malli.dev/start!)` does step 2 and instruments; after redefining a function, re-run
`(malli.instrument/collect! {:ns ['my.app.core]})` to re-emit; `(malli.dev/stop!)` empties the
types file.

```clojure
(order-total {:price "100"})   ; error: Expected: integer, received: string.
(order-total {:qty 2})         ; error: Missing required key: :price
```

Functions defined in `.cljs` files (shadow-cljs): copy the hooks from
`--lint "$(npx shadow-cljs classpath)"`, and get the types from a small node build that prints
them with malli's `print-cljs!`:

```clojure
;; shadow-cljs.edn, under :builds
:kondo-types {:target :node-script :main my.app.kondo-types/main :output-to "out/kondo-types.js"}

;; src/my/app/kondo_types.cljs
(ns my.app.kondo-types
  (:require [my.app.core]               ; every namespace whose functions get types
            [malli.instrument :as mi]
            [malli.clj-kondo :as mc]))

(defn main []
  (mi/collect! {:ns [my.app.core]})     ; a literal list: cljs collects at compile time
  (mc/print-cljs!))
```

```sh
npx shadow-cljs compile kondo-types
mkdir -p .clj-kondo/imports/metosin/malli-types-cljs
node out/kondo-types.js > .clj-kondo/imports/metosin/malli-types-cljs/config.edn
```

What is checked where:

- **Static** (clj-kondo, after step 2), at the call site:
  - a value of the wrong type in a row: `(order-total {:price "100"})`;
  - a missing required key: `(order-total {:qty 2})`;
  - a wrong type inside a nested map row: `(ship {:addr {:zip "x"}})`, `ship`'s row
    `:addr [:map [:zip :int]]`;
  - a wrong type through a local: `(let [p "100"] (order-total {:price p}))`;
  - another typed function's output: `(order-total {:price (label {:n 1})})`, `label` being `-> :string`.
- **Runtime** (malli instrumentation, dev/test only, see
  [Checks run in dev/test only](#checks-run-in-devtest-only)): the actual values, ranges
  (`{:price 0}` against `[:int {:min 1}]`), unknown keys of a closed map (`^{:closed true}`), and
  the output. clj-kondo's types carry no ranges and read every map as open.
- **Compile** (the macro, at every map-literal call; cljs: release builds, see
  [Compile-time literal checks](#compile-time-literal-checks)): unknown keys of a closed map,
  missing keys, and values that are data against their row schema, ranges included.
- **Release**: nothing, except the functions you opt in (see
  [malli in production](#malli-in-production)). The types live in `.clj-kondo`, instrumentation
  only in dev/test.

Using Claude Code? [examples/claude-code](examples/claude-code) gives the agent this check after every edit.

## Typed Clojure

[Typed Clojure](https://github.com/typedclojure/typedclojure) type-checks the code with the types
the signatures already declare: no annotation per function. It follows a value where clj-kondo
stops (a map built apart from the call) and checks the body against `->`.

The library does not depend on Typed Clojure. Add it to your dev alias:

```clojure
:aliases {:dev {:extra-deps {org.typedclojure/typed.clj.checker {:mvn/version "1.3.0"}
                             org.typedclojure/typed.malli {:mvn/version "1.3.0"}}}}
```

```clojure
(require '[defn-typed.typed-clojure :refer [install! check-form!]])

(install! {:namespaces ['my.app.core]})      ; again after reloading a namespace

(check-form! {:ns 'my.app.core :form form :file "src/my/app/core.clj"})
;; => [{:file "src/my/app/core.clj" :line 12 :column 3 :defn-typed? true
;;      :message "Function inc could not be applied to arguments: …"}]
```

- `install!` collects the namespaces' malli function schemas, from which typed.malli's var-type
  provider (registered by the `typedclojure_config.cljc` in its jar) types each function, and
  evaluates `t/ann` for each function's `<name>--positional` (where the body lives) and
  `<name>-props`, and for the `defn-typed.core` functions an expansion calls.
- `check-form!` checks one top-level form (read from the file with its `:line`) and returns every
  type error as data. `:defn-typed?` is true when the error is inside a `(defn-typed …)` form or
  its form calls a defn-typed function: a per-edit hook reports those first, while the rest of the
  code has no annotations yet.
- It checks with `:check-form-eval :never`. Typed Clojure's default, `:after`, evaluates every
  checked form again, which redefines the var without malli's instrumentation.
- Every form the macro generates without your code in it carries `^:typed.clojure/ignore`, so
  the checker sees your body and your calls, not the expansion.

What it finds, on the seven planted bugs of
[test-typed/defn_typed/typed_bugs.clj](test-typed/defn_typed/typed_bugs.clj):

| Bug | Example | Typed Clojure | clj-kondo |
|---|---|---|---|
| wrong type into a row through a local | `(let [id (label-of {:name "9"})] (cover-of {:lot_id id …}))` | yes | yes |
| a `:string` result used as a number | `(inc (label-of {:name "a"}))` | yes | yes |
| wrong type in a nested map literal | `(zip-of {:address {:zip "10115" :city "Berlin"}})` | yes | yes |
| a field of another function's result into a row | `(cover-of {:lot_id (:title m) …})`, `m` = `(summary-of {:id 9})` | yes | yes |
| a field of a result used as a number | `(inc (:title (summary-of {:id 9})))` | yes | yes |
| a nested map built apart from the call | `(let [addr {:zip (label-of …) :city "Berlin"}] (zip-of {:address addr}))` | yes | no |
| the body returns another type than `->` | `(defn-typed lot-count {:label :string} -> :int (str label))` | yes | no |
| | | **7 / 7** | **5 / 7** |

clj-kondo here = the exported hooks plus the malli types of step 2 above; with the hooks alone it
finds 2 of 7 (the two results used as numbers).

Timing: about 17 ms per form on a warm JVM (an application namespace); the fixture's 21 forms take
121 ms together (median 5 ms, slowest 23 ms). Check form by form, each with a deadline: over a very
large namespace, a whole-file `check-ns` may exhaust the checker's stack, and a checker thread that
runs away cannot be stopped, only abandoned (restart the JVM).

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

The three blocks run as a test (`test/defn_typed/readme_test.clj` evaluates them verbatim).

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
- A docstring, an attr-map, an argument vector, or `->` with nothing after it inside `defn-typed` is
  a compile error naming the function. Single arity only.

`defn-typed` expands to plain Clojure:

```clojure
(do (def name-props [:map [key schema] …])   ; a defaulted row: [key {:optional true} schema]
    (declare name)                            ; the body may call name
    (defn name--positional [key …] body…)
    (defn name {:malli/schema [:=> [:cat name-props] out-schema] :doc … :inline …
                :arglists '([{:keys [key …]}])}
      [m]
      (let [{:keys [key …] :or {key default …}} m]
        (name--positional key …))))
```

so clj-kondo (with the exported hooks), malli's `collect!`/`instrument!` and any tool that reads
a `defn` see a `defn`. `:arglists` lists the rows (`^{:as row}` adds `:as row`), so `(doc name)`
and editor hovers show the inputs: `([{:keys [price qty discount]}])`. The defaults are the `:or`
of the destructuring, taken from the rows when the macro expands. With `^{:as row}` the map goes through
`defn-typed.core/with-defaults` (the whole filled map is bound); a row whose defaults only the
evaluated schema shows (a symbol as its type, `:frame frame`, or a `[:map …]` row with defaults
inside) is read at call time. `<name>-props` keeps entry order up to 8 rows (a larger map literal
reads as a hash map; the order is cosmetic).

## Zero-cost calls

A map literal at a call site is named-argument syntax: `(order-total {:price p :qty 3})` names
the arguments for the compiler and builds no hash map. The body lives in `<name>--positional`,
whose parameters are the rows in entry order, and the compiler turns the literal call into the
positional call:

```clojure
(order-total {:price p :qty 3})
;; compiles to
(let [price__1 p qty__2 3] (order-total--positional price__1 qty__2 0))
```

Release and production builds always do it. Dev and test builds keep the map call (`<name>`
destructures the map and calls `<name>--positional`), so instrumentation can check the map.

The values are evaluated in the literal's order, as the map call evaluates them; an absent row
gets its default. Every other call is the map call: a map that is not a literal, a literal with a
key beyond the rows, a key that is not a keyword literal (`{k 1}`) or without a required key, a
literal that fails the checks below, `apply` and
higher-order uses, and every call of a function with `^{:as row}`, with a row read at call time, or
whose body `recur`s to the function (its `recur` takes the map).

The switch:

- **Clojure**: on by default. Every direct call is covered, `:refer`red ones included (the
  function's `:inline`). The setting `:inline false`, in effect while the calling code compiles,
  keeps the map call; dev/test aliases set it as a system property (see [Configuration](#configuration)).
  A literal call compiled to the positional call while `malli.instrument` is loaded prints one
  stderr line per JVM: `defn-typed: literal calls compile to positional calls, instrumentation will
  not check them — set -Ddefn-typed.inline=false in your dev/test alias`.
- **ClojureScript**: on in a release build (`:optimizations :advanced`), off in dev. Calls through
  an alias (`c/order-total`), a `:refer`red name, a qualified name, or inside the defining namespace
  are covered. In the release JS such a call builds no map: `(c/order-total {:price p :qty 3})` came
  out as `quot(300 * p, 100)`. A `.cljs` namespace holding defn-typed functions, and each namespace
  that `:refer`s one, is recompiled on every release build (shadow-cljs prints `Failed reading cache
  for <ns>: failed to require macro-ns …`): its cached analysis names a macro that exists only while
  the build runs.

A rewritten call skips `<name>`, so instrumentation does not see it: dev, REPL and test builds keep
the switch off, where every call goes through the var. With it on, a caller compiled earlier still
reaches a REPL redefinition of the body (it calls the `<name>--positional` var); a redefinition that
changes the rows leaves it calling the new `<name>--positional` with the old row order (so does a
caller compiled against an older version of the function).

A 3-row function with 2 defaults, `(total {:price p :qty 3})`, criterium `quick-bench` on JVM 21:
positional `defn` 36 ns, switch off 51 ns, switch on 28 ns (0.1.4, which filled the defaults by
walking the schema at every call: 814 ns).

### Compile-time literal checks

In Clojure, with the switch on or off, a map-literal call is checked where it compiles: an unknown key (of a
closed map, `^{:closed true}`; `[:map …]` is open), a missing required key (not judged when a key
is not a keyword literal, `{k 1}`), and each value that is data (a number, string, keyword, boolean, nil, or a
literal collection of those) against its row schema, ranges included. A map literal whose row is a
`[:map …]` (or `[:maybe [:map …]]`) is walked by the same rules, each finding led by its key path
(`:order :price — missing required key`); a value in it that is not data is skipped. By default a mismatch prints
one line to stderr and the call compiles to the map call; the build goes on:

```
WARNING defn-typed src/shop.clj:12: (order-total …) :qty 0 — should be at least 1
```

With `{:literal-check :error}` in `defn-typed.edn` the mismatch is a compile error instead (see
[Configuration](#configuration)).

ClojureScript: literal checks and the rewrite run in release (`:advanced`) builds; in dev,
clj-kondo and malli instrumentation cover the same calls.

Not checked here: a value that is not data, a row schema that cannot be evaluated at compile time
(in cljs, a schema with a symbol in it), a map that is not a literal. The value check runs only
where malli is already loaded: the dev REPL/test loaders load it first, so the files they (re)load
get value checks, and a Clojure server compiling from source never loads it, switch on or off (the
ClojureScript compiler loads it). Key checks always run.

## Configuration

`defn-typed.edn` holds a project's settings. The compiler looks for it in its working directory,
then in each parent directory up to the filesystem root, and the first one found wins (as
clj-kondo finds `.clj-kondo`): one file at a monorepo's root serves `clojure` run in `backend/` and
shadow-cljs run in `miniapp/`, and a nearer file overrides it. It is read once per JVM, at the
first macroexpansion; no file = the defaults.

```clojure
{:literal-check :warn   ; :warn (default) | :error
 :inline        true}   ; true (default) | false
```

- `:literal-check`: a literal call that fails its compile-time check (see Compile-time literal
  checks) prints its `WARNING defn-typed …` line and compiles to the map call (`:warn`), or is a
  compile error with the same text (`:error`): the namespace does not load, the build fails.
  Clojure and ClojureScript alike.
- `:inline`: whether a fitting literal call compiles to the positional call in Clojure (see
  Zero-cost calls). ClojureScript decides by the build: on in release, off in dev.

A JVM system property wins over the file, the file over the default:
`-Ddefn-typed.literal-check=warn|error`, `-Ddefn-typed.inline=false` (any other value is on). An
unknown key, or a value outside its list, is an error naming the key and the allowed values.

Strict mode, a literal that fails the check does not compile:

```clojure
;; defn-typed.edn
{:literal-check :error}
```

```
Syntax error (ExceptionInfo) compiling order-total at (shop.clj:12:3).
defn-typed shop.clj:12: (order-total …) :qty 0 — should be at least 1
```

Dev and test keep the map call, so instrumentation checks every call; their aliases set the
property (the file is shared with production builds):

```clojure
:aliases {:test  {:jvm-opts ["-Ddefn-typed.inline=false"] …}
          :nrepl {:jvm-opts ["-Ddefn-typed.inline=false"] …}}
```

## Checks run in dev/test only

- **Clojure**: a loader namespace on the test/REPL classpath only (never on the server's) requires
  the app and runs `(malli.instrument/collect! {:ns …})` + `(malli.instrument/instrument!)`. A bad
  call then throws `:malli.core/invalid-input` / `:malli.core/invalid-output`. A plain
  `(require 'ns :reload)` redefines the vars un-instrumented; re-collect + instrument after it.
- **ClojureScript**: the app calls `malli.dev.cljs/start!` under a dev define; the release build
  aliases it away (`:build-options {:ns-aliases {malli.dev.cljs malli.dev.cljs-noop}}`).
  `defmeta`'s registrations (cases, `:meta`, `#'f`) sit under `goog.DEBUG`, so a release build
  drops them. The released bundle has no malli code and no cases, unless the app requires
  `defn-typed.malli-in-prod`.
- `defn-typed`'s expansion contains no malli symbol (`:malli/schema` is a keyword in the attr-map):
  nothing it emits loads malli. `test/defn_typed/core_test.clj` `release-form` asserts this.

## malli in production

To keep checking chosen functions in production, add `:malli-in-prod` to their `defmeta`. For
those functions this is malli instrumentation at run time, in every build. It is not static
typing: each call's input map and result are validated by malli while the program runs.

```clojure
(defmeta place-bid
  {:doc           "Ставка на лот."
   :inout-tests   [...]
   :malli-in-prod true})                     ; or {:sample 0.01 :redact #{:phone :token}}

(defn-typed.malli-in-prod/on-malli-violation!
  (fn [{:keys [fn direction value errors schema stack at repeats]}] ...))
```

Require `defn-typed.malli-in-prod` once, at the app's entry point (clj and cljs). That brings
malli into the build and installs the checker. Without the require, an opted-in function runs
unchecked and prints one line: `:malli-in-prod on <fn> but defn-typed.malli-in-prod is not
loaded`. A cljs release with no opted-in function and no require has no malli code, as before.

- **What runs**: the input validator and the output validator, compiled once per function. On
  success nothing else runs and nothing is allocated: 72 bytes per call plain and 72 checked in
  the benchmark harness. On JVM 21 a 3-row function took 21 ns plain and 66 to 104 ns checked
  across runs; its two validators alone took 19 ns. The functions without `:malli-in-prod`
  are unchanged.
- **Guarantees**:
  - the function always returns its normal result;
  - the check never throws into the caller;
  - the handler runs off the call path: clj uses one background thread, cljs a 0 ms timeout;
  - a handler that throws is caught and prints one stderr line, at most once per 60 s.
- **The event** handed to the handler:
  - `:fn`, the qualified symbol;
  - `:direction`, `:input` or `:output`;
  - `:value`, the map or the result;
  - `:errors`, as `[{:path :value :message} …]`, where `:message` is malli's humanized text;
  - `:schema`, the schema's form;
  - `:stack`, the top frames as text, built only on a violation;
  - `:at`, epoch ms;
  - `:repeats`.
- **Redact**: the keys in `:redact` are removed from `:value` at any depth before the handler
  sees it. An error whose path goes through such a key carries no `:value`.
- **Dedupe**: a function reports one event per set of failing paths per 60 s. The violations
  inside that window are counted into the next event's `:repeats`. No handler registered: each
  event prints one stderr line, with paths and messages and no values.
- **Sample**: `:sample 0.01` checks about 1 % of calls, and the other calls run with no check.
  The draw is `defn-typed.malli-in-prod/*random*`.
- **Zero-cost calls**: the switch never rewrites an opted-in function's call to the positional
  call, because every call has to pass through the check.
- Opted-in functions are `defn-typed` only. A dev loader's instrumentation (which throws) still
  wraps the same function.

## Validating data with the same schema

`<name>-props` is a plain malli schema. Validate a form or API input with it directly:

```clojure
(me/humanize (m/explain order-total-props {:price 100 :qty 0}))   ; m = malli.core, me = malli.error
;; => {:qty ["should be at least 1"]}
```

Use it for forms and API input. It is the same schema the function's contract uses, and it works
in clj and in a cljs release build.

## clj-kondo

The hooks ship in `resources/clj-kondo.exports/io.github.hyperfocusdisordered/defn-typed/`; step 1 of
[Static checking](#static-checking) copies them to `.clj-kondo/imports/io.github.hyperfocusdisordered/defn-typed/`, which clj-kondo loads with no
further config (checked with clj-kondo v2026.01.19). The `defn-typed` hook lints the rows, the
arrow and the body as the `def` + `defn` above, with the row keys as locals, and reports the same
shape errors as the macro; the `defmeta` hook lints the map as code.

## Running the examples

- `(check-var #'f)` → `{:var sym :cases n :failures [{:i :in :expected :actual}]}`; a throwing
  case → `:actual [:thrown msg]`.
- `(check-ns 'ns)` → `check-var` over every function of `ns` that has examples.
- `(deftests! 'ns)` (clj) → one `clojure.test` test `<ns>--<fn>-inout` per such function, in the
  namespace that calls it, so `clojure -M:test` runs them with the rest of the suite; the ns in the
  name keeps two namespaces' functions of one name as two tests.
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
clojure -M:test                                     # clj, switch on (the default)
clojure -J-Ddefn-typed.inline=false -M:test         # clj, switch off
clojure -M:cljs compile test && node out/node-tests.js   # cljs (shadow-cljs :node-test)
clojure -M:cljs release inline && node out/inline-tests.js   # cljs release, switch on
clojure -M:typed                                    # Typed Clojure bridge (test-typed/)
clj-kondo --lint src test                            # uses the exported hooks
```

## Contributing / upstream

TBD.

## License

MIT — see `LICENSE`.
