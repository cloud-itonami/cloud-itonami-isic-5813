(ns pressops.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (pressops.operation -> pressops.governor ->
  pressops.store). No invented numbers, no timestamps, byte-identical
  across reruns."
  (:require [clojure.string :as str]
            [pressops.store :as store]
            [pressops.operation :as op]
            [pressops.phase :as phase]
            [pressops.governor :as governor]
            [pressops.advisor :as advisor]
            [langgraph.graph :as g]))

(def ^:private operator {:actor-id "editor-1" :actor-role :managing-editor :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "managing-editor-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real OperationActor StateGraph through a scenario built
  directly from `pressops.store/demo-data` and `pressops.governor`'s
  actual rules (this repo's own `pressops.sim` was run first and found
  trustworthy -- its ids/ops match the real seed data and rules exactly,
  confirmed by executing `clojure -M:dev:run` and cross-checking every
  disposition against store.cljc/governor.cljc, so this mirrors the same
  scenario rather than reusing sim.cljc's -main directly, to keep this
  namespace's demo self-contained):

    1. `:log-production-record` pub-1 -- clean, phase-3 auto-commit.
    2. `:schedule-production-operation` pub-1 -- clean, phase-3
       auto-commit.
    3. `:coordinate-distribution` pub-1 -- clean, phase-3 auto-commit.
    4. `:flag-content-concern` pub-1 -- a `governor/always-escalate-ops`
       op, ALWAYS escalates even when clean -> human (managing editor)
       approval -> commit.
    5. `:log-production-record` pub-99 -- pub-99 does not exist in
       `store/demo-data` at all -- HARD hold, rule
       `:publication-unverified`.
    6. `:log-production-record` pub-3 -- pub-3 IS registered but its
       own seeded `:verified?` is `false` -- HARD hold, rule
       `:publication-unverified` (the same rule as #5, re-derived from
       the store's own record rather than the proposal's claim, on a
       DIFFERENT publication -- shows the check is rule-based, not
       id-specific).
    7. `:schedule-production-operation` pub-1, advisor forced into
       claiming a direct `:effect :commit` instead of `:propose`
       (mirrors `pressops.sim`'s own `actor-direct` harness) -- HARD
       hold, rule `:effect-not-propose`.
    8. `:log-production-record` pub-1, `:out-of-scope? true` (the
       advisor's own test hook that appends editorial-content/legal/
       source-verification-sign-off language to its rationale) -- HARD
       hold, rule `:scope-excluded`, permanent regardless of op.

  Returns the seeded `db` (a `pressops.store/MemStore`) after the run,
  so `render` can read every value straight off it -- including
  `store/coordination-log`, the actual SSoT write-side, confirming the
  `:commit` node's `store/commit-record!` call really landed (not just
  the audit ledger)."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1" {:op :log-production-record :publication-id "pub-1"
                        :patch {:issue-status "final proof received" :print-run 12000}})

    (exec! actor "t2" {:op :schedule-production-operation :publication-id "pub-1"
                        :patch {:stage "layout" :date "2026-08-01"}})

    (exec! actor "t3" {:op :coordinate-distribution :publication-id "pub-1"
                        :patch {:channel "digital" :release-date "2026-08-03"}})

    (exec! actor "t4" {:op :flag-content-concern :publication-id "pub-1"
                        :patch {:concern "possible unverified claim about a named individual in the lead story"
                                :confidence 0.92}})
    (approve! actor "t4")

    (exec! actor "t5" {:op :log-production-record :publication-id "pub-99"
                        :patch {:issue-status "unknown"}})

    (exec! actor "t6" {:op :log-production-record :publication-id "pub-3"
                        :patch {:issue-status "draft"}})

    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "t7" {:op :schedule-production-operation :publication-id "pub-1"
                                 :patch {:stage "print-run"}}))

    (exec! actor "t8" {:op :log-production-record :publication-id "pub-1"
                        :out-of-scope? true
                        :patch {}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc
  "Minimal HTML-escape -- every rendered string passes through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for
  "The most recent ledger fact for `publication-id`, off the real
  subject-key field this repo's `commit-fact`/`hold-fact` records use:
  `:publication-id` (see `pressops.operation/commit-fact` and
  `pressops.governor/hold-fact`)."
  [ledger publication-id]
  (last (filter #(= publication-id (:publication-id %)) ledger)))

(defn- status-cell
  "[css-class label] for the last known ledger fact of a publication --
  the same cond pattern used fleet-wide."
  [fact]
  (cond
    (nil? fact)                        ["muted" "no activity"]
    (= :committed (:t fact))           ["ok" "committed"]
    (= :approval-granted (:t fact))    ["ok" "approved & committed"]
    (= :governor-hold (:t fact))       ["critical" (str "HARD hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))   ["err" "approval-rejected"]
    (= :approval-requested (:t fact))  ["warn" "awaiting approval"]
    :else                              ["muted" "in progress"]))

(defn- publications-table [db]
  (let [pubs (store/all-publications db)
        ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>publication_id</th><th>name</th><th>registered?</th><th>verified?</th><th>last status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [p pubs
            :let [fact (last-fact-for ledger (:publication-id p))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:publication-id p)) "</code></td>"
             "<td>" (esc (:name p)) "</td>"
             "<td>" (if (:registered? p) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (:verified? p) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

;; pub-99 never appears in `store/all-publications` (it was never
;; registered at all -- the governor's `publication-unverified`
;; violation catches BOTH "doesn't exist" and "exists but unverified"
;; under the same rule). Render it as its own explicit row sourced only
;; from the ledger, so the HARD-hold on a wholly-unregistered id isn't
;; silently invisible in the publications table above.
(defn- unregistered-attempts-table [db]
  (let [known (set (map :publication-id (store/all-publications db)))
        unknown-facts (filter #(and (= :governor-hold (:t %))
                                     (not (contains? known (:publication-id %))))
                               (store/ledger db))]
    (str
     "<table>\n<thead><tr>\n"
     "<th>publication_id</th><th>op</th><th>rule</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [f unknown-facts]
        (str "<tr>"
             "<td><code>" (esc (:publication-id f)) "</code></td>"
             "<td><code>" (esc (:op f)) "</code></td>"
             "<td class=\"critical\">" (esc (str/join "," (map name (:basis f)))) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- committed-records-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>op</th><th>publication_id</th><th>value</th><th>approved_by</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [r (store/coordination-log db)]
      (str "<tr>"
           "<td><code>" (esc (:op r)) "</code></td>"
           "<td><code>" (esc (:publication-id r)) "</code></td>"
           "<td><code>" (esc (pr-str (:value r))) "</code></td>"
           "<td>" (if-let [by (:approved-by (:payload r))] (esc by) "&mdash;") "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(defn- action-gate-table
  "Static op-contract description, sourced from the real
  `pressops.phase/phases` (phase 3, this actor's `default-phase`) and
  `pressops.governor/always-escalate-ops` -- not invented, just
  rendered."
  []
  (let [ph (get phase/phases phase/default-phase)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>op</th><th>phase-" phase/default-phase " write allowed?</th><th>auto-eligible?</th><th>always escalates?</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [op (sort governor/allowed-ops)]
        (str "<tr>"
             "<td><code>" (esc op) "</code></td>"
             "<td>" (if (contains? (:writes ph) op) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (contains? (:auto ph) op) "<span class=\"ok\">yes</span>" "no") "</td>"
             "<td>" (if (contains? governor/always-escalate-ops op) "<span class=\"critical\">yes</span>" "no") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- audit-ledger-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>t</th><th>op</th><th>publication_id</th><th>disposition</th><th>basis / rule</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [f (store/ledger db)]
      (str "<tr>"
           "<td>" (esc (:t f)) "</td>"
           "<td><code>" (esc (:op f)) "</code></td>"
           "<td><code>" (esc (:publication-id f)) "</code></td>"
           "<td class=\""
           (case (:disposition f) :commit "ok" :hold "err" "muted")
           "\">" (esc (:disposition f)) "</td>"
           "<td>" (if (seq (:basis f))
                    (str/join ", " (map (comp esc name) (:basis f)))
                    "&mdash;")
           "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str
   "<!doctype html>\n"
   "<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>pressops.render-html -- Press Operations Governor operator console</title>\n"
   "<style>\n" css "\n</style>\n"
   "</head>\n<body>\n"
   "<header class=\"bar\"><h1>Press Operations Governor -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 5813 &middot; phase " phase/default-phase " (" (:label (get phase/phases phase/default-phase)) ")</span>"
   "</header>\n"
   "<main>\n"
   "<div class=\"card\">\n<h2>Publications</h2>\n" (publications-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Unregistered-publication attempts (never in the publication directory at all)</h2>\n" (unregistered-attempts-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Committed coordination records (production-record log / schedule / distribution / content-concern flag)</h2>\n" (committed-records-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Action gate (pressops.phase &middot; pressops.governor/always-escalate-ops)</h2>\n" (action-gate-table) "\n</div>\n"
   "<div class=\"card\">\n<h2>Audit ledger</h2>\n" (audit-ledger-table db) "\n</div>\n"
   "</main>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
