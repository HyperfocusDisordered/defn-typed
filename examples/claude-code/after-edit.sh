#!/usr/bin/env bash
# Claude Code PostToolUse hook (Edit|Write) for a Clojure project using defn-typed.
# After an edit of a .clj/.cljc/.cljs file it runs, in order:
#   1. reload the file's ns over nREPL                          (needs .nrepl-port, .clj/.cljc)
#   2. malli collect! for that ns + rewrite its clj-kondo types (same)
#   3. clj-kondo --lint <file>
#   4. (defn-typed.core/check-ns '<ns>)                          (same as 1)
# and hands Claude ONE line, `OK <ns> · lint 0 · cases N/N`, or only the failures.
# Needs: babashka (bb) and clj-kondo on PATH. Always exits 0: its own trouble never fails the edit.

input=$(cat)
command -v bb >/dev/null 2>&1 || exit 0

file=$(printf '%s' "$input" | bb -e '(print (or (-> (cheshire.core/parse-string (slurp *in*) true) :tool_input :file_path) ""))' 2>/dev/null)
case "$file" in *.clj|*.cljc|*.cljs) ;; *) exit 0 ;; esac
[ -f "$file" ] || exit 0
file=$(cd "$(dirname "$file")" && pwd)/$(basename "$file")

# project root = the nearest directory up from the file holding .nrepl-port, else a project file
root=""; dir=$(dirname "$file")
while [ "$dir" != "/" ]; do
  if [ -f "$dir/.nrepl-port" ]; then root=$dir; break; fi
  dir=$(dirname "$dir")
done
if [ -z "$root" ]; then
  dir=$(dirname "$file")
  while [ "$dir" != "/" ]; do
    for marker in deps.edn shadow-cljs.edn project.clj bb.edn .clj-kondo; do
      if [ -e "$dir/$marker" ]; then root=$dir; break 2; fi
    done
    dir=$(dirname "$dir")
  done
fi
[ -n "$root" ] || root=$(dirname "$file")

ns=$(sed -n 's/^(ns[[:space:]]\{1,\}\(\^[^[:space:]]*[[:space:]]\{1,\}\)\{0,1\}\([^[:space:])]*\).*/\2/p' "$file" | head -1)
[ -n "$ns" ] || ns=$(basename "$file")

# nrepl_eval CODE → prints the eval's last value, or `ERROR <message>` (exception, no answer)
nrepl_eval() {
  PORT=$port CODE=$1 bb -e '
(require (quote [bencode.core :as b]))
(let [text #(if (bytes? %) (String. % "UTF-8") (str %))
      socket (doto (java.net.Socket.) (.connect (java.net.InetSocketAddress. "127.0.0.1" (parse-long (System/getenv "PORT"))) 1000) (.setSoTimeout 60000))
      in (java.io.PushbackInputStream. (.getInputStream socket))
      out (.getOutputStream socket)]
  (b/write-bencode out {"op" "eval" "id" "1" "code" (System/getenv "CODE")})
  (.flush out)
  (loop [value nil err ""]
    (let [m (b/read-bencode in)
          value (or (some-> (get m "value") text) value)
          err (str err (some-> (get m "err") text))
          status (set (map text (get m "status")))]
      (cond (status "eval-error") (do (Thread/sleep 50) (println "ERROR" (first (remove clojure.string/blank? (clojure.string/split-lines err)))))
            (status "done") (println value)
            :else (recur value err)))))' 2>/dev/null || echo "ERROR nREPL on port $port did not answer"
}

port=""
[ -f "$root/.nrepl-port" ] && [ "${file##*.}" != "cljs" ] && port=$(tr -dc '0-9' < "$root/.nrepl-port")

problems=()

# 1 + 2: reload, collect, rewrite this ns's entry in .clj-kondo/imports/metosin/malli-types-clj
if [ -n "$port" ]; then
  reload=$(nrepl_eval "
(require 'defn-typed.core 'malli.instrument 'malli.clj-kondo 'clojure.edn)
(let [ns-sym '$ns
      root \"$root\"]
  (try
    (defn-typed.core/forget-ns! ns-sym)
    (require ns-sym :reload)
    (malli.instrument/collect! {:ns [ns-sym]})
    (let [f (clojure.java.io/file root \".clj-kondo\" \"imports\" \"metosin\" \"malli-types-clj\" \"config.edn\")
          old (if (.exists f) (clojure.edn/read-string (slurp f)) {})
          types (get-in (malli.clj-kondo/linter-config (malli.clj-kondo/collect ns-sym))
                        [:linters :type-mismatch :namespaces ns-sym])]
      (malli.clj-kondo/save!
        (-> (merge {:linters {:unresolved-symbol {:exclude ['(malli.core/=>)]}}} old)
            (update-in [:linters :type-mismatch :namespaces] #(if types (assoc % ns-sym types) (dissoc % ns-sym))))
        :clj {:clj-kondo-dir-path [root]}))
    :ok
    (catch Throwable e
      (str \"RELOAD ERROR \" (ex-message e) (some->> (ex-cause e) ex-message (str \" · \"))))))")
  case "$reload" in
    :ok) ;;
    ERROR*) problems+=("$reload"); reloaded=no ;;
    *) problems+=("$(printf '%s' "$reload" | bb -e '(print (read-string (slurp *in*)))' 2>/dev/null)"); reloaded=no ;;
  esac
fi

# 3: lint the file against the project's .clj-kondo (hooks + the types just written)
lint=$(cd "$root" && clj-kondo --lint "$file" 2>&1 | grep -E ':[0-9]+:[0-9]+: (error|warning): ')
lint_count=0
if [ -n "$lint" ]; then
  lint_count=$(printf '%s\n' "$lint" | wc -l | tr -d ' ')
  while IFS= read -r line; do problems+=("LINT ${line#"$root"/}"); done <<< "$lint"
fi

# 4: the defmeta cases of the ns
cases=""
if [ -n "$port" ] && [ "$reloaded" != no ]; then
  cases=$(nrepl_eval "
(let [results (defn-typed.core/check-ns '$ns)
      total (reduce + 0 (map :cases results))
      failures (for [{:keys [var failures]} results, f failures]
                 (str \"CASE \" var \" #\" (:i f) \" in \" (pr-str (:in f))
                      \" expected \" (pr-str (:expected f)) \" actual \" (pr-str (:actual f))))]
  (clojure.string/join \"\\n\" (cons (str (- total (count failures)) \"/\" total) failures)))")
  case "$cases" in
    ERROR*) problems+=("$cases"); cases="" ;;
    *) cases=$(printf '%s' "$cases" | bb -e '(print (read-string (slurp *in*)))' 2>/dev/null)
       while IFS= read -r line; do case "$line" in CASE*) problems+=("$line") ;; esac; done <<< "$cases"
       cases=$(printf '%s\n' "$cases" | head -1) ;;
  esac
fi

if [ ${#problems[@]} -eq 0 ]; then
  message="OK $ns · lint $lint_count${cases:+ · cases $cases}"
else
  message=$(printf '%s\n' "${problems[@]}" | head -12)
fi
MESSAGE=$message bb -e '(println (cheshire.core/generate-string {:hookSpecificOutput {:hookEventName "PostToolUse" :additionalContext (System/getenv "MESSAGE")}}))' 2>/dev/null
exit 0
