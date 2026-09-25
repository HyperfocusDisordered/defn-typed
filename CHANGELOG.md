# Changelog

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
