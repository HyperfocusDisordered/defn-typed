# Changelog

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
