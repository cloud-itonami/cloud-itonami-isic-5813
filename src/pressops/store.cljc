(ns pressops.store
  "SSoT for the ISIC-5813 newspaper/journal/periodical-publishing
  COORDINATION actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every `cloud-itonami-isic-*`
  actor in this fleet uses.

  This actor coordinates the back-office operations of a periodical
  publishing house: issue/edition/print-run production-record logging,
  editing/layout/print-run scheduling proposals, outbound distribution/
  circulation coordination, and content-risk-concern flagging
  (defamation, sourcing-integrity, factual-accuracy concerns raised by
  an editor or the advisor). It never touches finalizing an editorial-
  content decision (what a story/issue actually says, whether it runs
  as written), a legal-risk clearance decision, or a source-verification
  sign-off decision -- see `pressops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `publications` directory keyed by `:publication-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt).

  A registered/verified publication record (the publishing house's own
  record of a newspaper/journal/periodical masthead) must exist before
  ANY proposal for it may ever commit or escalate --
  `pressops.governor`'s `publication-unverified-violations` re-derives
  this from the publication's own `:registered?`/`:verified?` fields,
  never from proposal self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which publication a proposal targeted,
  which operation, on what basis, committed/held/escalated and approved
  by whom is always a query over an immutable log.")

(defprotocol Store
  (publication [s publication-id] "Registered publication record, or nil.
    Publication map: {:publication-id .. :name .. :registered? bool :verified? bool}.")
  (all-publications [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-publications [s publications] "replace/seed the publication directory (map publication-id->publication)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained publication directory covering both the
  happy path and the governor's own hard checks, so the actor + tests
  run offline."
  []
  {:publications
   {"pub-1" {:publication-id "pub-1" :name "The Daily Harbor Herald (registered masthead, editorial charter verified)"
             :registered? true :verified? true}
    "pub-2" {:publication-id "pub-2" :name "Quarterly Civic Review (registered masthead, editorial charter verified)"
             :registered? true :verified? true}
    "pub-3" {:publication-id "pub-3" :name "Undisclosed Community Bulletin (masthead registered, editorial charter pending verification)"
             :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (publication [_ publication-id] (get-in @a [:publications publication-id]))
  (all-publications [_] (sort-by :publication-id (vals (:publications @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-publications [s publications] (when (seq publications) (swap! a assoc :publications publications)) s))

(defn seed-db
  "A MemStore seeded with the demo publication directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `publications` map (publication-id
  string -> publication map) -- the primary test/dev entry point.
  `publications` may be empty (an unregistered-everywhere store)."
  [publications]
  (->MemStore (atom {:publications (or publications {}) :ledger [] :coordination-log []})))
