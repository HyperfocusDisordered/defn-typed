(ns defn-typed.compile-err
  "What a cljs compile prints while it expands a form, for a cljs test to assert on.")

(defmacro expansion-err
  "What the cljs analyzer's macroexpansion of form (written quoted, so linters leave it alone), in
   this call's env, prints to stderr, as a string literal; the expansion itself is dropped (nothing
   of form is compiled)."
  [[_quote form]]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      ((requiring-resolve 'cljs.analyzer/macroexpand-1) &env form))
    (str err)))
