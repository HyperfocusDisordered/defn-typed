# Claude Code: check every Clojure edit

`after-edit.sh` is a Claude Code PostToolUse hook. After each edit of a `.clj`/`.cljc`/`.cljs` file
it hands the agent one line, `OK <ns> · lint 0 · cases N/N`, or only the failures. In order:

1. Reload the file's namespace over nREPL: compile errors (`RELOAD ERROR …`).
2. `defn-typed.kondo/emit!` over `src/` and `test/` into `.clj-kondo/imports/defn-typed/types/config.edn`
   (in the nREPL; without one, the same through `clojure -M -m defn-typed.kondo`), so step 3 sees
   the edited signatures.
3. `clj-kondo --lint <file>` with the defn-typed hooks and those types: shape errors and calls
   that break a signature, including one just changed (`LINT …`).
4. `(defn-typed.core/check-ns '<ns>)`: the `defmeta` example pairs (`CASE <fn> #i in … expected … actual …`).

Steps 1 and 4 run when the project root holds `.nrepl-port` (a running nREPL with the dev
properties, e.g. `clojure -J-Ddefn-typed.dev=true -J-Ddefn-typed.inline=false -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M -m nrepl.cmdline`)
and the file is `.clj`/`.cljc`; otherwise steps 2 (through the CLI) and 3. Without
`-Ddefn-typed.dev=true` step 4 finds no cases. Install: copy `after-edit.sh` to `.claude/hooks/` (executable), merge
`settings.json` into `.claude/settings.json`, and do step 1 of the main README's Static checking
(copy the hooks). Needs [babashka](https://babashka.org) and clj-kondo on `PATH`.
