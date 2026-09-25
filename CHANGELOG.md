# Changelog

## 0.3.0 (2026-09-25)

- malli in production: `:malli-in-prod true` (or `{:sample 0.01 :redact #{:phone}}`) in a
  function's `defmeta` makes malli check that function's input map and result at run time in
  every build, once the app requires `defn-typed.malli-in-prod` (new namespace). Without that
  require, the function runs unchecked and prints one line. The check never changes the result
  and never throws into the caller; validators are compiled once per function, and a successful
  check allocates nothing. Violations reach `defn-typed.malli-in-prod/on-malli-violation!` off the
  call path (clj: one background thread, cljs: a 0 ms timeout) as
  `{:fn :direction :value :errors :schema :stack :at :repeats}`: `:redact` keys are removed at any
  depth, there is one event per function and failing paths per 60 s (the rest counted in
  `:repeats`), and a throwing handler is caught. `defn-typed.core` still never loads malli: a cljs
  release without the require has no malli code. The switch never rewrites an opted-in call to the
  positional call.
- README: "Validating data with the same schema" — `<name>-props` as a plain malli schema for
  forms and API input (clj and cljs `:advanced`).
- cljs: the call-site expander decides per compile. A shadow-cljs JVM that ran a release no longer
  runs stale literal checks in its later dev compiles (rv3 N1).
- A `recur` written by a macro in the body is found (the body is macroexpanded first), so it keeps
  the map path instead of throwing ClassCastException (rv3 N2).
- clj-kondo hook: `^{:as a}` with a row `:a` is the finding `:a and ^{:as a} both bind a`, the same
  as the macro (rv3 N3).
- cljs literal checks judge numbers as JS numbers at any depth: `1.0` fits `:int`, `[1 2]` fits
  `[:vector :double]`, and a double schema's props still apply (rv3 M2 class).

## 0.2.1 (2026-09-25)

- A body may call its own function by name (a recursive call, or the name passed as a value): clj
  compiles it again, cljs has no `:undeclared-var` warning.
- A clj server compiling from source no longer loads malli: the compile-time value check runs only
  when malli is already loaded (dev REPL/test loaders load it first); key checks always run.
- A body whose `recur` targets the function keeps it in the map fn, so `recur` takes the map as in
  0.1.4 (1 row: no ClassCastException; 2+ rows: compiles).
- cljs: an integer literal fits a `:double` row in the compile-time check (cljs numbers are
  doubles), so `(scale {:x 1})` neither warns nor falls back to the map call.
- An unknown key warns only for a closed input map (`^{:closed true}`); a key that is not a keyword
  literal (`{k 100}`) skips the key checks and the rewrite.
- cljs: the call-site macro exists in release (`:advanced`) builds only; dev builds keep the
  shadow-cljs cache (before, every namespace defining a `defn-typed` recompiled on every build)
  and compile plain calls.
- README: the switch is for release builds; with it on, a REPL redefinition can leave stale
  positional call sites.
- clj-kondo hook and macro: `(defmeta 5 …)`, `(defn-typed)`, an odd input map and two rows binding
  one local are a finding naming the problem (the file's other findings stay) and a compile error.
- A row whose type or props slot is a symbol and carries a default is `{:optional true}` in
  `<name>-props`, so an instrumented call may omit it.
- README: a cljs `:refer`red call from another namespace gets neither the literal check nor the
  rewrite.
- Literal-check warnings read `<key> <value> — <reason>`: a part inside the value leads with its
  path (`at 1: should be a keyword`); without a malli message, `does not match <schema>`.

## 0.2.0 (2026-09-25)

- Defaults cost nothing at call time: they are the `:or` of the function's destructuring, taken
  from the rows when the macro expands. `with-defaults` still runs for `^{:as row}` functions,
  and `row-value` (new) for a row whose defaults only the evaluated schema shows (a symbol as its
  type, a `[:map …]` row with defaults inside). Results are unchanged: an explicit nil stays nil,
  a default written as a call is evaluated once, at the def.
- The body lives in `<name>--positional` (the rows as parameters, entry order); `<name>` destructures
  and calls it.
- Zero-cost calls: with the switch on (clj: JVM property `defn-typed.inline=true` at compile time;
  cljs: `:optimizations :advanced`), a call whose argument is a map literal of known keys holding
  every required row compiles to the positional call, values evaluated in the literal's order.
  clj covers every direct call (`:inline`); cljs covers calls through an alias, a qualified name or
  inside the defining namespace (a macro of the same name), not a `:refer`red call from another
  namespace.
- Compile-time literal checks, switch on or off: an unknown key, a missing required key, or a data
  value its row schema rejects prints `WARNING defn-typed <file>:<line>: (f …) <findings>` to
  stderr and the call compiles to the map call.
- New public fns: `expand-call` (the call-site expander), `row-value`.

## 0.1.4 (2026-09-25)

- `(defn-typed f {…} ->)` with nothing after `->` is a compile error naming the function ("no output
  schema after ->"); the clj-kondo hook reports it too (before, the macro compiled it with a `nil`
  output schema and the hook stayed silent).
- README: "Static checking" — copying the hooks, emitting malli's clj-kondo types (clj/cljc via
  `malli.clj-kondo/emit!`, cljs via `malli.clj-kondo/print-cljs!`), and what is checked statically,
  at runtime and in a release.

## 0.1.3 (2026-09-25)

- clj-kondo hook: a `defn-typed` without `->` reports "expected ->" and the rest of the file still lints
  (before, clj-kondo printed "Can't parse <file>" and dropped every other finding of that file).

## 0.1.2 (2026-09-25)

- First release on Clojars (`io.github.hyperfocusdisordered/defn-typed`); library code unchanged since 0.1.1.
- Release tooling: `build.clj` (`clojure -T:build jar | deploy`), GitHub Actions CI (clj, cljs, clj-kondo).

## 0.1.1 (2026-09-25)

- A default = `:default` in the row type's own props, type first: `:qty [:int {:min 1 :default 1}]`
  (malli's schema default). `defn-typed` marks such a row `{:optional true}` itself
  (`defaults-optional`, nested maps included); `with-defaults` reads the type's props.
- `:default` in a row's entry props (`key [{:default v} schema]`) = compile error and clj-kondo
  error naming the fix; entry props stay for `{:optional true}` without a default.
- Removed: `defaulted-required-keys`, `defaulted-key-rule` and `malli-reasons`' defaulted-key line;
  `malli-fns` no longer returns `:form`. Added: `schema-props`, `entry-default-paths`,
  `entry-default-rule`, `defaults-optional`.

## 0.1.0 (2026-09-25)

- `defn-typed` + `defmeta`, extracted from a production Clojure/ClojureScript app.
- Check API: `check-var`, `check-vars`, `check-ns`, `case-vars`, `registered-vars`,
  `undefined-metas`, `forget-ns!`, `deftests!`, `test-ns!`, `test-var!`, `malli-reasons`,
  `malli-fns`, `*trace-cases*`, `with-defaults`; legacy `tests`.
- clj-kondo hooks exported at `resources/clj-kondo.exports/io.github.hyperfocusdisordered/defn-typed/`.
- malli dependency 0.20.1; tested on 0.11.0 (oldest release both test suites pass on) and 0.20.1.
