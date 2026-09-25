# Claude Code: check every Clojure edit

`after-edit.sh` is a Claude Code PostToolUse hook. After each edit of a `.clj`/`.cljc`/`.cljs` file
it hands the agent one line, `OK <ns> · lint 0 · cases N/N`, or only the failures. In order:

1. Reload the file's namespace over nREPL: compile errors (`RELOAD ERROR …`).
2. `malli.instrument/collect!` for that namespace, then rewrite its entry in
   `.clj-kondo/imports/metosin/malli-types-clj/config.edn`, so step 3 sees the edited signatures.
3. `clj-kondo --lint <file>` with the defn-typed hooks and those types: shape errors and calls
   that break a signature, including one just changed (`LINT …`).
4. `(defn-typed.core/check-ns '<ns>)`: the `defmeta` example pairs (`CASE <fn> #i in … expected … actual …`).

Steps 1, 2 and 4 run when the project root holds `.nrepl-port` (a running nREPL, e.g.
`clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M -m nrepl.cmdline`) and the file is `.clj`/`.cljc`;
otherwise only step 3. Install: copy `after-edit.sh` to `.claude/hooks/` (executable), merge
`settings.json` into `.claude/settings.json`, and do step 1 of the main README's Static checking
(copy the hooks). Needs [babashka](https://babashka.org) and clj-kondo on `PATH`.
