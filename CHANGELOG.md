# Changelog

## Unreleased

- docs: tagline on README/pom/GitHub, one arity by design.
- malli floor measured: every suite passes on malli 0.16.3 and later; the 0.3.2 README's 0.11.0
  was wrong (the clj suite fails below 0.16.3).
- docs: README opens with one complete example, Install with the dev/test alias, what runs where,
  the REPL and adoption; CHANGELOG 0.3.2 lists every user-visible change, Breaking first;
  `examples/claude-code` emits types with `defn-typed.kondo/emit!`.
- The jar ships no clj-kondo `.cache` (0.3.2 carried one under `clj-kondo.exports/`): removed from git, ignored, and `build.clj` drops any `.cache` dir before jarring.

## 0.3.2 (2026-10-15)

**Breaking**

- Without `-Ddefn-typed.dev=true` (or `:dev true` in `defn-typed.edn`) a Clojure JVM runs nothing
  at load: `check-ns` returns `[]`, `deftests!` defines 0 tests, `defn-typed.core/instrument!`
  returns nil, and a bad real-map call goes unchecked, with no message. 0.3.1's dev alias
  (`-Ddefn-typed.inline=false` alone) therefore checks nothing on 0.3.2. Upgrade: add
  `-Ddefn-typed.dev=true` to the dev, test and REPL aliases. ClojureScript keeps `goog.DEBUG`.
- Metadata on the input map (`^{:as row}`, `^{:closed true}`) is a compile error; data the body
  needs whole is a row (`{:row :map}`).

**Added**

- `:dev` setting (`-Ddefn-typed.dev=true`), the Clojure analogue of `goog.DEBUG`, off by default:
  a release JVM loading a namespace only defines the functions — no in/out cases, no `defmeta`
  registrations, no unknown-key check on real-map calls, no instrumentation upkeep, no
  `:stale-callers` signatures or watches, no Typed Clojure check. ClojureScript's unknown-key
  check moves under `goog.DEBUG` (a release build has none).
- Generic functions: type variables in a vector before the input map,
  `(defn-typed first-of [T] {:xs [:sequential T]} -> [:maybe T] …)`; Typed Clojure gets
  `(t/All [T] …)`, malli and clj-kondo read `:any`. Loading `defn-typed.typed-clojure` types
  `[:tuple …]` as `t/HVec`.
- `defnt` = `defn-typed` under a short name (same expansion, hooks and checks).
- In/out cases run as the definition loads (clj with `:dev`, cljs dev builds): `:inout-check
  :warn` (default) prints one line per failing case naming the differing key paths, or the
  exception a case threw; `:error` fails the load; `:off` runs nothing. `assert-cases` (and
  `deftests!`) carries the same line.
- Typed Clojure check at load (clj, `:dev` on, Typed Clojure on the classpath): `:typed-check
  :warn|:error|:off`, up to 2 s per definition. Findings lead with `input of <f>:` or
  `output of <f>:`, and `check-form!` returns `:kind` (`:input`, `:output`, nil).
- `:report-success`: one line per namespace whose defn-typed checks all passed.
- `:stale-callers` (clj, `:dev` on): a redefinition with another signature names the namespaces
  whose literal calls compiled against the old one (`:warn`, default), reloads them (`:reload`,
  opt-in) or does nothing (`:off`); a row typed by a schema var (`:order Order`) follows a change
  of that var.
- `:unknown-keys :warn|:error|:off`: a real map holding keys beyond the rows, in dev builds (at
  most 1000 reported pairs per process). `:literal-check` takes `:off`. `:literal-check`,
  `:unknown-keys`, `:typed-check` and `:inout-check` can be set per function in its `defmeta`.
- A row or output schema malli cannot build is a compile error with malli's message and the
  file:line; schema names from a custom registry must be qualified.
- Defaults inside `:sequential` / `:vector` / `:maybe` rows: optional in `<name>-props`, filled at
  any depth; literal items are checked by index.
- `defn-typed.kondo/emit! {:src-dirs [...] :out path}` (CLI `clojure -M -m defn-typed.kondo --src-dir
  src --out <path>`) writes the clj-kondo types of every `defn-typed` / `defnt` and every `defn`
  with `:malli/schema` under the dirs, read from the source (`.clj`, `.cljc`, `.cljs`) without
  loading it; output canonical, written only on change. `defn-typed.core/form-parts` = the macro's
  reading of a form, public.
- `defn-typed.core/instrument!` (malli's `instrument!` options): a rejection's message reads
  `defn-typed: <fn> — invalid :<key>: expected <schema>, got <value>` (malli's ex-data unchanged;
  a `:report` of yours wins), and a redefinition re-instruments with the same options. A no-op with
  `:dev` off.
- Instrumentation survives a reload: a defn-typed function redefined from its file (a plain
  `require :reload` included) stays instrumented.
- A private defn-typed function (`^:private`, or `:private true` in its `defmeta`) is instrumented
  in dev like a public one; its `<name>--positional`, `<name>-props` (and `:malli-in-prod` vars)
  are private too.
- A map-literal call compiled before its callee's definition (the callee `declare`d above it)
  compiles as a map call and is reported by the definition under `:literal-check`:
  `defn-typed: <file>:<line> calls <fn> before its definition — …` (`:error` fails, `:off` silent).
- Every ex-info the library throws carries `:type`, one of `:defn-typed.core/invalid-form`,
  `invalid-schema`, `invalid-cases`, `setting`, `literal-call`, `forward-call`, `unknown-keys`,
  `inout-failure`, `typed-check`.
- cljs dev builds check map-literal calls too: a `shadow-cljs watch` prints `WARNING defn-typed <file>:<line>: …` for a wrong literal call (`:refer`red, qualified, aliased or inside the defining namespace) and the call stays the map call; release builds are unchanged. `{:literal-check :off}` keeps a dev build without the call-site macro.
- Literal calls report every key beyond the rows, of open maps too (0.2.1 flagged closed maps only).
- clj-kondo hook: lints the `def` and both `defn`s the macro defines, so a direct `name--positional`
  call resolves and its arity is checked; the linted `defn` carries the `defmeta`'s `:doc`
  (clojure-lsp hover); a row the body never reads is an unused binding; the exported config keeps
  clojure-lsp from reporting `<name>-props` and `<name>--positional` as unused public vars.
- The pom lists the Typed Clojure deps as `provided`, so cljdoc analyzes `defn-typed.typed-clojure`.

**Changed**

- A literal call whose values are all data or symbols compiles to `(name--positional v …)` with no
  `let`; a value that is a call keeps the `let` (evaluation order).
- A leading `{:pre … :post …}` holds wherever the body lands (also a body that `recur`s to the
  function, or a table of more than 20 rows); `:post` beside such a `recur` is a compile error, as
  in `fn`.
- The first build of a fresh shadow-cljs JVM recompiles every namespace that holds a defn-typed function or requires one (its cached analysis names the call-site macro); builds in a running `watch` compile only what changed.

**Fixed**

- In/out cases that call a function declared above and defined further down keep waiting for it when shadow-cljs recompiles a namespace over its earlier analysis.

**Docs and tests**

- README: every setting (default, scope, when read, property, per-function key), `defnt`, the
  `:dev` flag, an "Escape hatch" section, an "Errors" section; "Editor types (clj-kondo)" replaces
  the manual `malli.clj-kondo/emit!` / `:node-script` recipe.
- In/out cases for the library's pure public functions, written from outside with `defmeta` (`test/defn_typed/api_cases.cljc`, `test-typed/defn_typed/typed_api_cases.clj`).

## 0.3.1 (2026-09-26)

- `defn-typed` arglists show the real row names, and generated in/out test names use `<ns>--<fn>-inout`.
- clj literal calls inline by default (`-Ddefn-typed.inline=false` turns it off); inlining while `malli.instrument` is loaded prints one stderr warning.
- cljs release builds rewrite `:refer`red literal calls to positional calls.
- `defn-typed.edn` project configuration supports `:literal-check :warn|:error` and `:inline`, looked up from the working directory up; property settings take precedence over the file, which takes precedence over defaults.
- Literal checks walk nested map literals and report the full key path.
- Typed Clojure support marks generated plumbing `^:typed.clojure/ignore`; optional `defn-typed.typed-clojure` provides `install!` and `check-form!`, while the library itself does not depend on Typed Clojure.

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
