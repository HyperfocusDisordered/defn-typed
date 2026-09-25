# Changelog

## 0.1.0 (2026-09-25)

- `defn-typed` + `defmeta`, extracted from the bikes-auction app (`bikes.auction.inout`, where the
  macro was named `defnmalli`); behaviour unchanged.
- Check API: `check-var`, `check-vars`, `check-ns`, `case-vars`, `registered-vars`,
  `undefined-metas`, `forget-ns!`, `deftests!`, `test-ns!`, `test-var!`, `malli-reasons`,
  `malli-fns`, `*trace-cases*`, `with-defaults`; legacy `tests`.
- clj-kondo hooks exported at `resources/clj-kondo.exports/io.github.denisovchar/inout/`.
- malli dependency 0.20.1; tested on 0.11.0 (oldest release both test suites pass on) and 0.20.1.
