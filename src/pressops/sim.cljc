(ns pressops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean production-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a production-operation scheduling
  request and a distribution coordination request (both auto-commit
  clean at phase 3), then a content-concern flag (ALWAYS escalates, at
  any phase -- approve, then commit), then HARD-hold scenarios: an
  unregistered publication, a publication registered but not yet
  verified, a proposal whose own `:effect` is not `:propose`, and a
  proposal that has drifted into the permanently-excluded
  editorial-content-decision/legal-risk-clearance/
  source-verification-sign-off scope."
  (:require [langgraph.graph :as g]
            [pressops.advisor :as advisor]
            [pressops.store :as store]
            [pressops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "managing-editor-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        editor-phase-1 {:actor-id "editor-1" :actor-role :managing-editor :phase 1}
        editor-phase-3 {:actor-id "editor-1" :actor-role :managing-editor :phase 3}
        actor (op/build db)]

    (println "== log-production-record pub-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-production-record :publication-id "pub-1"
                                  :patch {:issue-status "final proof received" :print-run 12000}} editor-phase-1)]
      (println r)
      (println "-- human managing editor approves --")
      (println (approve! actor "t1")))

    (println "\n== log-production-record pub-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-production-record :publication-id "pub-1"
                                  :patch {:issn-assigned "2049-3630" :print-run 12000}} editor-phase-3))

    (println "\n== schedule-production-operation pub-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-production-operation :publication-id "pub-1"
                                  :patch {:stage "layout" :date "2026-08-01"}} editor-phase-3))

    (println "\n== coordinate-distribution pub-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-distribution :publication-id "pub-1"
                                  :patch {:channel "digital" :release-date "2026-08-03"}} editor-phase-3))

    (println "\n== flag-content-concern pub-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-content-concern :publication-id "pub-1"
                                 :patch {:concern "possible unverified claim about a named individual in the lead story" :confidence 0.92}} editor-phase-3)]
      (println r)
      (println "-- human managing editor reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-production-record pub-99 (unregistered publication -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-production-record :publication-id "pub-99"
                                  :patch {:issue-status "unknown"}} editor-phase-3))

    (println "\n== log-production-record pub-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-production-record :publication-id "pub-3"
                                  :patch {:issue-status "draft"}} editor-phase-3))

    (println "\n== schedule-production-operation pub-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-production-operation :publication-id "pub-1"
                                           :patch {:stage "print-run"}} editor-phase-3)))

    (println "\n== log-production-record pub-1, advisor drifts into editorial-content/legal/sourcing scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-production-record :publication-id "pub-1"
                                   :out-of-scope? true
                                   :patch {}} editor-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
