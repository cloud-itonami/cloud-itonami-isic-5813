(ns pressops.advisor
  "PressAdvisor -- the *contained intelligence node* for the
  ISIC-5813 newspaper/journal/periodical-publishing
  operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: production-record logging (issue/edition/print-run data),
  production-operation scheduling (editing/layout/print-run
  scheduling), content-concern flagging (defamation/sourcing-integrity/
  factual-accuracy risk), and distribution coordination (outbound
  distribution/circulation coordination). CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by
  `pressops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a finalized editorial-content decision
  (what a story/issue says, whether it runs as written), a legal-risk
  clearance decision, or a source-verification sign-off decision --
  those are permanently out of scope for this actor, not merely
  un-implemented. `pressops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode
  (a compromised or confused advisor drifting into scope it must never
  touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op             kw             ; echoes the request op
     :publication-id str
     :summary        str            ; human-facing draft / finding
     :rationale      str            ; why -- SCANNED by the scope-exclusion gate
     :cites          [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect         :propose       ; ALWAYS :propose -- never a direct actuation
     :value          map            ; the draft payload a human/system would review
     :confidence     0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-production-record
  "Draft a production-record log entry: issue/edition number, print-run
  quantity, ISSN-assignment data. Pure back-office logging of observed
  production facts -- never an editorial-content judgment."
  [_db {:keys [publication-id patch]}]
  {:op             :log-production-record
   :publication-id publication-id
   :summary        (str publication-id " の制作記録（号建て/版/刷り部数/ISSN割当）を記録: " (pr-str (keys patch)))
   :rationale      "号建て・版・印刷部数・ISSN割当などの制作事実の記録のみ。編集内容の判断なし。"
   :cites          [publication-id]
   :effect         :propose
   :value          (merge {:publication-id publication-id} patch)
   :confidence     0.94})

(defn- propose-production-schedule
  "Draft an editing/layout/print-run scheduling proposal (a calendar
  entry, never a direct press-line dispatch or a sign-off on the
  edited content itself)."
  [_db {:keys [publication-id patch]}]
  {:op             :schedule-production-operation
   :publication-id publication-id
   :summary        (str publication-id " の制作工程（編集/組版/印刷）スケジュールを提案: " (pr-str (keys patch)))
   :rationale      "編集・組版（レイアウト）・印刷工程の日程調整のみ。編集内容そのものの確定判断ではない。"
   :cites          [publication-id]
   :effect         :propose
   :value          (merge {:publication-id publication-id} patch)
   :confidence     0.89})

(defn- propose-distribution-coordination
  "Draft an outbound distribution/circulation coordination proposal
  (delivery route/newsstand/digital-release scheduling only -- never a
  finalized editorial call on what ships)."
  [_db {:keys [publication-id patch]}]
  {:op             :coordinate-distribution
   :publication-id publication-id
   :summary        (str publication-id " の配送/購読流通・デジタル配信の調整を提案: " (pr-str (keys patch)))
   :rationale      "印刷部数の配送ルート・販売店配本・デジタル配信のスケジュール調整のみを行い、内容の確定は伴わない。"
   :cites          [publication-id]
   :effect         :propose
   :value          (merge {:publication-id publication-id} patch)
   :confidence     0.91})

(defn- propose-content-concern
  "Surface a content-risk concern (potential defamation, sourcing-
  integrity gap, or factual-accuracy concern observed in a story or
  proof) for HUMAN triage. This op ALWAYS escalates in
  `pressops.governor` -- never auto-committed at any phase -- regardless
  of how confident the advisor is that the concern is real."
  [_db {:keys [publication-id patch]}]
  {:op             :flag-content-concern
   :publication-id publication-id
   :summary        (str publication-id " のコンテンツ懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale      "名誉毀損リスク・出典/裏付けの未確認・事実確認の懸念に関する観察事実の報告。常に人間の確認・判断が必要。"
   :cites          [publication-id]
   :effect         :propose
   :value          (merge {:publication-id publication-id} patch)
   :confidence     (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-production-record (propose-production-record _db request)
                   :schedule-production-operation (propose-production-schedule _db request)
                   :coordinate-distribution (propose-distribution-coordination _db request)
                   :flag-content-concern (propose-content-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the editorial-content decision and issued a source-verification sign-off")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t              :advisor-proposal
   :op             (:op proposal)
   :publication-id (:publication-id proposal)
   :summary        (:summary proposal)
   :confidence     (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
