# defn-typed

[![test](https://github.com/HyperfocusDisordered/defn-typed/actions/workflows/test.yml/badge.svg)](https://github.com/HyperfocusDisordered/defn-typed/actions/workflows/test.yml)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.hyperfocusdisordered/defn-typed.svg)](https://clojars.org/io.github.hyperfocusdisordered/defn-typed)
[![cljdoc badge](https://cljdoc.org/badge/io.github.hyperfocusdisordered/defn-typed)](https://cljdoc.org/d/io.github.hyperfocusdisordered/defn-typed)

Typed defn for Clojure and ClojureScript: one signature gives Typed Clojure static checks, compile-time literal-call checks, clj-kondo editor types, malli runtime contracts and example tests — and a call costs a positional call.

## Why

Most languages give you one familiar shape for a typed function: name, inputs with their types,
output type, body.

TypeScript

```typescript
function orderTotal(price: number, qty: number = 1, discount: number = 0): number
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
- **type checking** (optional, clj): Typed Clojure checks bodies and call sites against the same
  signature, including a body that returns a different type from its `->`, with no hand-written annotations (see Typed Clojure);
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
after `(malli.dev/start!)`, in tests after `(defn-typed.core/instrument!)`. Without instrumentation
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
{:deps {io.github.hyperfocusdisordered/defn-typed {:mvn/version "0.3.2"}}}

;; deps.edn, from git
{:deps {io.github.hyperfocusdisordered/defn-typed {:git/tag "v0.3.2" :git/sha "a01b52f"}}}

;; shadow-cljs.edn
{:dependencies [[io.github.hyperfocusdisordered/defn-typed "0.3.2"]]}
```

`metosin/malli` comes along as a dependency (see [malli versions](#malli-versions)).

## Static checking

clj-kondo reports wrong calls of `defn-typed` functions once their types are in the project's
`.clj-kondo` directory. In the consuming project:

```sh
# 1. the defn-typed hooks (once, and again after upgrading the library)
mkdir -p .clj-kondo   # clj-kondo copies configs only into an existing config dir
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint

# 2. the types (again after a signature change; see Editor types below)
clojure -M -m defn-typed.kondo --src-dir src --out .clj-kondo/imports/defn-typed/types/config.edn

# 3. lint as usual
clj-kondo --lint src
```

With the hooks alone clj-kondo checks the form's shape and the body, not the types of the calls.

```clojure
(order-total {:price "100"})   ; error: Expected: integer, received: string.
(order-total {:qty 2})         ; error: Missing required key: :price
```

### Editor types (clj-kondo)

`defn-typed.kondo` reads the source files and writes the clj-kondo config of every typed function
under the dirs: each `defn-typed` / `defnt` (its input read by the macro's own `form-parts`, a
defaulted row optional) and each plain `defn` carrying `:malli/schema`. It loads no namespace, so
`.clj`, `.cljc` and `.cljs` files are covered alike (a `.cljs` file through its `:cljs` reader
branches), and it runs in any JVM with the library on the classpath:

```clojure
(defn-typed.kondo/emit! {:src-dirs ["src" "test"] :out ".clj-kondo/imports/defn-typed/types/config.edn"})
;; => {:written? true :fns 42 :out ".clj-kondo/imports/defn-typed/types/config.edn"}
```

```sh
clojure -M -m defn-typed.kondo --src-dir src --src-dir test --out .clj-kondo/imports/defn-typed/types/config.edn
# stderr: OK 42 fns → .clj-kondo/imports/defn-typed/types/config.edn   (stdout stays empty)
```

clj-kondo loads a config under `.clj-kondo/imports/` with no further setting; another path needs
`:config-paths`. In a schema, a symbol naming a top-level `def` of the same namespace (or of the
namespace it is `:refer`red from) stands for that def's value, and `[:fn …]` for `:any`. The
output is canonical (every map sorted by key) and the file is written only when its text changes,
so the same sources leave it, and its git diff, alone; `:written? false` says so. malli's global
function-schema registry is not touched, so a REPL's own registrations stay as they are.

An after-edit hook keeps the types current by calling it in the project's warm REPL (nREPL) before
it lints the edited file, and falling back to the CLI when no REPL runs:

```sh
# after-edit hook, $file under src/ edited; nrepl-eval = your nREPL client (fails when no REPL runs)
form='(defn-typed.kondo/emit! {:src-dirs ["src"] :out ".clj-kondo/imports/defn-typed/types/config.edn"})'
nrepl-eval "(do (require 'defn-typed.kondo) $form)" \
  || clojure -M -m defn-typed.kondo --src-dir src --out .clj-kondo/imports/defn-typed/types/config.edn
clj-kondo --lint "$file"
```

What is checked where:

- **Static** (clj-kondo, once the types are emitted), at the call site:
  - a value of the wrong type in a row: `(order-total {:price "100"})`;
  - a missing required key: `(order-total {:qty 2})`;
  - a wrong type inside a nested map row: `(ship {:addr {:zip "x"}})`, `ship`'s row
    `:addr [:map [:zip :int]]`;
  - a wrong type through a local: `(let [p "100"] (order-total {:price p}))`;
  - another typed function's output: `(order-total {:price (label {:n 1})})`, `label` being `-> :string`.
- **Runtime** (malli instrumentation, dev/test only, see
  [Checks run in dev/test only](#checks-run-in-devtest-only)): the actual values, ranges
  (`{:price 0}` against `[:int {:min 1}]`) and the output. Unknown keys: literal calls flag them
  at compile time, real maps in dev builds (`:unknown-keys`); malli checks maps as open maps. clj-kondo's types carry no ranges and read every
  map as open.
- **Compile** (the macro, at every map-literal call; cljs: every build, see
  [Compile-time literal checks](#compile-time-literal-checks)): unknown keys, missing keys, and values that are data against their row schema, ranges included.
- **Release**: nothing, except the functions you opt in (see
  [malli in production](#malli-in-production)). The types live in `.clj-kondo`, instrumentation
  only in dev/test.

Using Claude Code? [examples/claude-code](examples/claude-code) gives the agent this check after every edit.

## Typed Clojure

[Typed Clojure](https://github.com/typedclojure/typedclojure) type-checks the code with the types
the signatures already declare: no annotation per function. It follows a value where clj-kondo
stops (a map built apart from the call) and checks the body against `->`.

The library does not depend on Typed Clojure. Add it to your dev alias (with `:dev` on, every
definition is then checked as it loads, see `:typed-check` in [Configuration](#configuration)):

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
  `<name>-props`, and for the `defn-typed.core` functions an expansion calls. A function with type
  variables gets its own `t/ann` too, `(t/All [T] …)` (see Generic functions).
- Loading `defn-typed.typed-clojure` teaches typed.malli the `[:tuple …]` schema:
  `[:tuple :int :string]` is `(t/HVec [t/AnyInteger t/Str])` (typed.malli alone reads it as `t/Any`).
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

clj-kondo here = the exported hooks plus the types of step 2 above (defn-typed.kondo); with the hooks alone it
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
(defn-typed name [T …] {key schema …} -> out-schema body…)   ; with type variables, see Generic functions
```

- `defmeta` goes before the `defn-typed` of the same name: the macro reads it to put `:doc` on
  the function. `defmeta` declares `name`.
- `defnt` is `defn-typed` under a short name: `(defnt name {…} -> out body…)` expands the same.
- **Input** = one map literal `{key schema …}`. Keys are keywords; qualified keys bind by their
  name (`:x/b` → `b`).
- **Defaults** = `:default` in the type's own props, `:qty [:int {:min 1 :default 1}]`; that row
  is optional by itself.
- **Row props** `key [props schema]` (a value vector whose first element is a map) = `:optional`
  without a default; `:default` there is a compile error.
- **Output** = any malli schema after `->`.
- **Body**: no argument vector — every row key is already a local.
- **`:inout-tests`** = `[in out]` pairs, `in` = the function's single argument (the map; or the
  scalar of a one-argument plain `defn`). A case passes iff `(= out (f in))`. A non-pair throws
  naming the var.
  As the definition loads, a failing case prints one line naming what differs: the key paths
  (maps are compared key by key, every other value as a whole), then the case's input:

  ```
  WARNING src/shop/order.clj:4 shop.order/place-order in/out case 0: [:sku] expected "bmx", got "sd" — input {:order {:sku "sd", :address {:city "Минск"}}}
  ```

  A key only in `out` reads `[:qty] expected 1, missing`, a key only in the result
  `[:gift] unexpected false`; two differences are joined by `; `.
  A case that throws names the exception first:

  ```
  WARNING src/shop/order.clj:4 shop.order/place-order in/out case 1: threw Divide by zero (expected 0) — input {:n 0}
  ```

  `assert-cases` (and so `deftests!`, `test-var!`, `test-ns!`) gives a failing case's assertion that
  same line as its message.
- A docstring, an attr-map, an argument vector, or `->` with nothing after it inside `defn-typed` is
  a compile error naming the function. A `defn-typed` function takes one map argument by design: its
  named rows with defaults replace arities, and multi-arity, variadic or positional functions
  stay `defn` (see Escape hatch).
- A condition map `{:pre […] :post […]}` first in the body works as in `fn`, wherever the body
  lands (`<name>--positional`, or the function itself when its body `recur`s to it or it has more
  than 20 rows). As in `fn`, `:post` beside a `recur` to the function is a compile error (`Can only
  recur from tail position`).
- A row or output schema malli cannot build is a compile error naming the function, the row,
  malli's error and the file:line; a schema held in a var is judged as the definition loads. clj
  judges once malli is loaded (a dev/test/REPL loader, the Typed Clojure bridge), cljs builds
  always. Names from a custom registry must be qualified (malli's own names are unqualified): they
  are trusted at expansion and checked when the registry is live.

  ```
  defn-typed: shop.price/order-total — invalid schema for :qty: unknown schema :it (did you mean :int?) — :malli.core/invalid-schema {:schema :it} (src/shop/price.clj:10)
  ```

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
a `defn` see a `defn`. `:arglists` lists the rows, so `(doc name)`
and editor hovers show the inputs: `([{:keys [price qty discount]}])`. The defaults are the `:or`
of the destructuring, taken from the rows when the macro expands. A row whose defaults only the
evaluated schema shows (a symbol as its type, `:frame frame`, or a `[:map …]` row with defaults
inside) is read at call time. `<name>-props` keeps entry order up to 8 rows (a larger map literal
reads as a hash map; the order is cosmetic).

## Generic functions

A function whose output type follows the type of its input lists type variables in a vector
between its name and its input map:

<!-- readme-test -->
```clojure
(defmeta first-of
  {:doc "Первый элемент списка — того же типа, что его элементы."
   :inout-tests [[{:xs [3 4]} 3]
                 [{:xs []}    nil]]})

(defn-typed first-of [T] {:xs [:sequential T]} -> [:maybe T]
  (first xs)
)
```

Each symbol of the vector is a type variable in every schema of the signature, the rows and the
output, at any depth.

- malli has no type variables: the macro writes `[:any {:defn-typed/type-var "T"}]` in T's place,
  so instrumentation, `<name>-props`, the compile-time literal checks and the clj-kondo types read
  `:any`. ClojureScript expands the same way (Typed Clojure is Clojure only).
- Typed Clojure annotates the function and its `<name>--positional` with `(t/All [T] …)`, so a
  call's result has the type of what went in: `(inc (or (first-of {:xs [1 2]}) 0))` checks, and
  `(inc (or (first-of {:xs ["a"]}) 0))` does not. The same function with `:any` in T's place
  returns `t/Any`: `Function inc could not be applied to arguments: … Arguments: typed.clojure/Any`.
- T can sit inside any schema the Typed Clojure bridge converts: `[:sequential T]`, `[:vector T]`,
  `[:set T]`, `[:map-of :keyword T]` (the values' type), `[:maybe T]`, `[:or T :int]`,
  `[:map [:k T]]`, `[:tuple T :int]` (`(t/HVec [T t/AnyInteger])`), and a schema a function builds:
  `{:b (box T)}`, with `(defn box [t] [:map [:value t]])`, is a type of your own over T. A registry
  schema (`[:ref ::box]`) is one fixed schema: a type variable cannot pass into it.
- A type variable no schema uses, or a vector that is not distinct symbols, is a compile error
  naming the function: `defn-typed f: no schema of the signature uses the type variable U`.

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
higher-order uses, and every call of a function with a row read at call time, or
whose body `recur`s to the function (its `recur` takes the map). A literal call compiled before
the function's definition (the name `declare`d above it) is a map call too, and the definition
reports it under `:literal-check`:

```
defn-typed: src/shop/db.clj:812 calls shop.db/lot-vertical before its definition — the call compiles as a map, not positionally; move the definition above the call
```

The switch:

- **Clojure**: on by default. Every direct call is covered, `:refer`red ones included (the
  function's `:inline`). The setting `:inline false`, in effect while the calling code compiles,
  keeps the map call; dev/test aliases set it as a system property (see [Configuration](#configuration)).
  A literal call compiled to the positional call while `malli.instrument` is loaded prints one
  stderr line per JVM: `defn-typed: literal calls compile to positional calls, instrumentation will
  not check them — set -Ddefn-typed.inline=false in your dev/test alias`.
- **ClojureScript**: on in a release build (`:optimizations :advanced`), off in dev, where the call
  stays the map call and is checked (see [Compile-time literal checks](#compile-time-literal-checks)).
  Calls through an alias (`c/order-total`), a `:refer`red name, a qualified name, or inside the
  defining namespace are covered. In the release JS such a call builds no map:
  `(c/order-total {:price p :qty 3})` came out as `quot(300 * p, 100)`. A `.cljs` namespace holding
  defn-typed functions, and each namespace that requires one, is recompiled by the first build of a
  fresh shadow-cljs JVM, dev or release (shadow-cljs prints `Failed reading cache for <ns>: failed
  to require macro-ns …`): its cached analysis names a macro that exists only while the build runs.
  Builds in a running `watch` compile only what changed. `{:literal-check :off}` in `defn-typed.edn`
  keeps a dev build free of that recompile.

A rewritten call skips `<name>`, so instrumentation does not see it: dev, REPL and test builds keep
the switch off, where every call goes through the var. With it on, a caller compiled earlier still
reaches a REPL redefinition of the body (it calls the `<name>--positional` var). A redefinition that
changes the rows makes those callers stale: they call the new `<name>--positional` with the old row
order. With `:dev` on, the redefinition prints the namespaces to reload (`:stale-callers :warn`,
the default):

```
defn-typed: shop.price/order-total changed its signature; callers compiled with the old one: shop.cart — reload them
```

`:stale-callers :reload` reloads them itself (see [Configuration](#configuration)).

A 3-row function with 2 defaults, `(total {:price p :qty 3})`, criterium `quick-bench` on JVM 21:
positional `defn` 36 ns, switch off 51 ns, switch on 28 ns (0.1.4, which filled the defaults by
walking the schema at every call: 814 ns).

### Compile-time literal checks

In Clojure, with the switch on or off, a map-literal call is checked where it compiles: an unknown key (any key
that is not a row), a missing required key (not judged when a key
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

ClojureScript: literal checks run in every build, dev watch included, and print the same
`WARNING defn-typed <file>:<line>: …` line; the rewrite to the positional call runs in release
(`:advanced`) builds only. In dev, clj-kondo and malli instrumentation cover the same calls too.

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
{:dev            false   ; false (default) | true
 :literal-check  :warn   ; :warn (default) | :error | :off
 :unknown-keys   :warn   ; :warn (default) | :error | :off
 :inline         true    ; true (default) | false
 :stale-callers  :warn   ; :warn (default) | :reload | :off
 :typed-check    :warn   ; :warn (default) | :error | :off
 :inout-check    :warn   ; :warn (default) | :error | :off
 :report-success false}  ; false (default) | true
```

| Setting | Default | Where it applies | Read when | Per function (`defmeta`) |
|---|---|---|---|---|
| `:dev` | `false` | Clojure (ClojureScript: `goog.DEBUG`) | each definition's expansion | — |
| `:literal-check` | `:warn` | Clojure, ClojureScript | each call site's compile | yes, for its call sites |
| `:unknown-keys` | `:warn` | dev builds (`:dev` / `goog.DEBUG`) | the definition's expansion (baked into the function) | yes |
| `:inline` | `true` | Clojure (ClojureScript: release builds) | each call site's compile | — |
| `:stale-callers` | `:warn` | Clojure, `:dev` on | at a redefinition (run time) | — |
| `:typed-check` | `:warn` | Clojure, `:dev` on, Typed Clojure on the classpath | the definition's load | yes |
| `:inout-check` | `:warn` | dev builds | the definition's expansion | yes |
| `:report-success` | `false` | Clojure, `:dev` on | each file load (run time) | — |

The four per-function settings go into the function's `defmeta` (`{:inout-check :off …}`) and
win over the project's.

- `:dev`: the Clojure analogue of `goog.DEBUG`, one switch for everything a definition does at
  load besides defining the function. Dev, test and REPL JVMs set it with
  `-Ddefn-typed.dev=true` (see the aliases below); production leaves it unset.

  What a release JVM (`:dev false`) does when it loads a namespace: nothing beyond defining the
  functions. It runs no in/out cases, registers no `defmeta` cases, emits no unknown-key check on
  real-map calls, keeps no instrumentation upkeep, puts no `:stale-callers` watch and records no
  signature, and runs no Typed Clojure check. Compile-time work stays: literal calls are checked
  (`:literal-check`) and rewritten to positional calls (`:inline`) in every build.
  ClojureScript has no `:dev` setting: `goog.DEBUG` is its signal, so a release build drops the same
  things and a dev build keeps them.
- `:literal-check`: a literal call that fails its compile-time check (see Compile-time literal
  checks) prints its `WARNING defn-typed …` line and compiles to the map call (`:warn`), or is a
  compile error with the same text (`:error`): the namespace does not load, the build fails.
  Clojure and ClojureScript alike; `:off` in a ClojureScript dev build also skips the call-site
  macro (see Zero-cost calls).
  `:off` checks nothing.
- `:unknown-keys`: a real map (not a literal) holding keys beyond the rows, in a dev build. `:warn`
  prints `defn-typed: <ns/fn> unknown key :k — the keys are :a :b` once per function and set of
  unknown keys (up to 1000 such pairs per process, then one muted line); `:error` throws; `:off`
  emits no check. A release build has no check.
- `:inline`: whether a fitting literal call compiles to the positional call in Clojure (see
  Zero-cost calls). ClojureScript decides by the build: on in release, off in dev.
- `:stale-callers` (Clojure, `:dev` on): what a redefinition does to the namespaces whose literal
  calls compiled against the old signature (a changed row order, or a changed schema var a row
  refers to). `:warn` (default) prints one line naming them; `:reload` (opt-in) reloads each
  caller's namespace from disk: top-level effects re-run, REPL-only edits in it are lost; `:off`
  does nothing.
- `:typed-check` (Clojure, `:dev` on): with Typed Clojure on the classpath, each definition is
  type-checked as it loads (see Typed Clojure): `:warn` prints each finding, `:error` fails the
  load, `:off` checks nothing. A check runs up to 2 s per definition; the first one loads the
  checker (seconds).
- `:inout-check` (dev builds): the `defmeta` cases run as the definition loads: `:warn` prints one
  line per failing case, `:error` fails the load, `:off` runs nothing.
- `:report-success`: after a file loads (`require`, `load-file`, an editor's load-file), one line
  per namespace whose defn-typed checks all passed (schemas, in/out cases, Typed Clojure), on the
  stream the `WARNING` lines use:

  ```
  defn-typed ✓ shop.order: 3 fns · 7 in/out cases · types ok
  ```

  `types ok` when Typed Clojure checked every function of the namespace with no finding, else
  `types skipped` (Typed Clojure not on the classpath, a function's `:typed-check :off`, a check
  past its deadline). A namespace with a failure (a `WARNING` line, a check that stopped the load)
  gets no line. Off by default: like malli's instrumentation and spec, a passing check prints
  nothing. Clojure only. The line comes within 25 ms after the load ends; a form evaluated at the
  REPL, outside a file load, gets none; a load stopped by an error outside defn-typed (a syntax
  error further down) still reports the definitions before it.

A JVM system property `-Ddefn-typed.<key>` wins over the file, the file over the default:
`-Ddefn-typed.dev=true` and `-Ddefn-typed.report-success=true` (any other value is off),
`-Ddefn-typed.inline=false` (any other value is on), `-Ddefn-typed.literal-check`,
`-Ddefn-typed.unknown-keys`, `-Ddefn-typed.typed-check` and `-Ddefn-typed.inout-check` `=warn|error|off`,
`-Ddefn-typed.stale-callers=warn|reload|off`. ClojureScript reads the same file and properties in
the compiler's JVM. An unknown key, or a value outside its list, is an error naming the key and
the allowed values.

Strict mode, a literal that fails the check does not compile:

```clojure
;; defn-typed.edn
{:literal-check :error}
```

```
Syntax error (ExceptionInfo) compiling order-total at (shop.clj:12:3).
defn-typed shop.clj:12: (order-total …) :qty 0 — should be at least 1
```

Dev and test turn the dev behaviour on and keep the map call, so instrumentation checks every
call; their aliases set the properties (the file is shared with production builds):

```clojure
:aliases {:test  {:jvm-opts ["-Ddefn-typed.dev=true" "-Ddefn-typed.inline=false"] …}
          :nrepl {:jvm-opts ["-Ddefn-typed.dev=true" "-Ddefn-typed.inline=false"] …}}
```

## Checks run in dev/test only

- **Clojure**: a loader namespace on the test/REPL classpath only (never on the server's), in a
  JVM with `-Ddefn-typed.dev=true`, requires the app and runs `(malli.instrument/collect! {:ns …})`
  + `(defn-typed.core/instrument!)`. A bad call then throws `:malli.core/invalid-input` /
  `:malli.core/invalid-output` (ex-data as malli builds it) with a message naming the function,
  the key, the expected schema and the value:

  ```
  defn-typed: shop.price/order-total — invalid :qty: expected [:int {:min 1}], got 0
  ```

  `defn-typed.core/instrument!` takes malli's `instrument!` options (`:filters`, `:report`,
  `:scope`, `:gen`); a `:report` of yours wins over that message. A definition loaded again is
  re-instrumented with the same options. `(malli.instrument/instrument!)` works too, with malli's
  message (`:malli.core/invalid-input`) and malli's default options after a redefinition. With
  `:dev` off, `defn-typed.core/instrument!` does nothing and returns nil.
  A private function is instrumented too: malli's `collect!` reads public vars only, so in dev a
  private definition registers its own schema. A plain `(require 'ns :reload)` keeps the
  instrumentation of every defn-typed function it redefines.
- **ClojureScript**: the app calls `malli.dev.cljs/start!` under a dev define; the release build
  aliases it away (`:build-options {:ns-aliases {malli.dev.cljs malli.dev.cljs-noop}}`).
  `defmeta`'s registrations (cases, `:meta`, `#'f`) sit under `goog.DEBUG`, so a release build
  drops them. The released bundle has no malli code and no cases, unless the app requires
  `defn-typed.malli-in-prod`.
- `defn-typed`'s expansion contains no malli symbol (`:malli/schema` is a keyword in the attr-map):
  nothing it emits loads malli. `test/defn_typed/core_test.clj` `release-form` asserts this.

## Errors

Every exception defn-typed throws is an `ex-info` whose `(:type (ex-data e))` is one of:

| `:type` | thrown when |
|---|---|
| `:defn-typed.core/invalid-form` | a `defn-typed` / `defmeta` form is malformed (no name, a docstring, an argument vector, no `->`, metadata on the input map, …) |
| `:defn-typed.core/invalid-schema` | a row or output schema malli cannot build |
| `:defn-typed.core/invalid-cases` | `:inout-tests` (or legacy `tests`) is not a vector of pairs |
| `:defn-typed.core/setting` | `defn-typed.edn` or a `defn-typed.*` system property holds an unknown key or a value outside its list |
| `:defn-typed.core/literal-call` | a map-literal call fails its compile-time check under `:literal-check :error` |
| `:defn-typed.core/forward-call` | a map-literal call compiled before its callee's definition, under `:literal-check :error` |
| `:defn-typed.core/unknown-keys` | a real map holds keys beyond the rows, under `:unknown-keys :error` |
| `:defn-typed.core/inout-failure` | an in/out case fails as the definition loads, under `:inout-check :error` |
| `:defn-typed.core/typed-check` | Typed Clojure reports a finding as the definition loads, under `:typed-check :error` |

A rejection by malli's instrumentation keeps malli's ex-data (`:malli.core/invalid-input`,
`:malli.core/invalid-output`); `defn-typed.core/instrument!` only rewrites its message.

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
arrow and the body as the `def` and the two `defn`s above, with the row keys as locals, so a direct
`name--positional` call resolves and its arity is checked, and reports the same shape errors as the
macro; a row the body never reads is clj-kondo's `unused binding` at its key; the `defn` it lints
carries the `:doc` of the `defmeta` above, so clojure-lsp's hover shows it. The `defmeta` hook
lints the map as code. The exported config keeps clojure-lsp from
reporting `name-props` and `name--positional` as unused public vars (checked with clojure-lsp
2026.07.06).

## Running the examples

- `(check-var #'f)` → `{:var sym :cases n :failures [{:i :in :expected :actual}]}`; a throwing
  case → `:actual [:thrown msg]`.
- `(check-ns 'ns)` → `check-var` over every function of `ns` that has examples.
- `(deftests! 'ns)` (clj) → one `clojure.test` test `<ns>--<fn>-inout` per such function, in the
  namespace that calls it, so `clojure -M:test` runs them with the rest of the suite; the ns in the
  name keeps two namespaces' functions of one name as two tests.
- A function with several positional arguments registers its examples with
  `(tests #'f [[[args…] out] …])`; `defmeta` pairs take one argument.

## Escape hatch

A plain `defn` sits beside defn-typed functions: multi-arity, variadic or positional functions
stay `defn`, with a hand-written schema and their cases still in `defmeta` above (cases of a
one-argument function are `[in out]` pairs; several positional arguments register through `tests`):

```clojure
(defmeta clamp {:doc "n held between lo and hi."})

(defn clamp
  {:malli/schema [:=> [:cat :int :int :int] :int]}
  [n lo hi]
  (max lo (min hi n)))

(defn-typed.core/tests #'clamp [[[5 0 3] 3] [[-1 0 3] 0]])
```

To move a function off the library, write the `defn` its expansion shows (see Syntax): delete the
input table and `->`, keep the destructuring.

```clojure
(defn order-total [{:keys [price qty discount] :or {qty 1 discount 0}}]
  (quot (* price qty (- 100 discount)) 100))
```

Its call sites need no change: a literal-map call is a map call.

## Compared with `malli.experimental/defn`

- `mx/defn` annotates positional arguments Plumatic-style (`[x :- :int, y :- :int]`), supports
  multi-arity, and expands to a `defn` followed by `(malli.core/=> name schema)` — a runtime call
  into `malli.core` at load.
- `defn-typed` takes one map argument whose rows are the schema, binds every row as a local,
  fills defaults from the rows, and stores the schema as `:malli/schema` metadata only (no malli
  call in the expansion). The example pairs live beside it in `defmeta`. One arity by design: named rows with
  defaults replace arities, and multi-arity, variadic or positional functions stay `defn`.

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
