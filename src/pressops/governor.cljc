(ns pressops.governor
  "PressGovernor -- the independent compliance layer that earns
  the PressAdvisor the right to commit. The advisor has no notion of
  whether a publication is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into
  a permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (production-record logging, production-operation scheduling,
  content-concern flagging, distribution coordination). It NEVER
  performs or authorizes:
    - finalizing an editorial-content decision (what a story/issue
      actually says, whether it runs as written)
    - a legal-risk clearance decision (defamation/libel risk sign-off)
    - a source-verification sign-off decision (declaring sourcing/
      fact-checking of a story complete and cleared)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Publication unverified   -- the target publication record (the
                                    publishing house's own record of a
                                    newspaper/journal/periodical
                                    masthead) must exist AND be
                                    independently confirmed
                                    `:registered?`/`:verified?` in the
                                    store before ANY proposal for it
                                    may commit or even escalate. Never
                                    trusts a proposal's own claim about
                                    the publication -- re-derived from
                                    the publication's own store record,
                                    the same 'ground truth, not
                                    self-report' discipline every
                                    sibling actor's governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST
                                    be `:propose`. Any other effect
                                    value is, by construction, a
                                    claim to directly actuate/commit
                                    outside governance -- HARD block,
                                    not merely low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                    whose op, rationale, summary,
                                    citations or draft value touches
                                    finalizing-an-editorial-content-
                                    decision / legal-risk-clearance /
                                    source-verification-sign-off
                                    territory is a HARD, PERMANENT
                                    block -- this actor's charter
                                    excludes that territory
                                    structurally, not as a rollout
                                    milestone. Evaluated
                                    UNCONDITIONALLY on every proposal.
                                    An op outside the closed four-op
                                    allowlist is the SAME failure mode
                                    (an advisor proposing something it
                                    was never authorized to propose)
                                    and is folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-content-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `pressops.phase` independently agrees: `:flag-content-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one.

  NOTE on scope-excluded-terms phrasing (a known self-tripping bug in
  this actor family): terms are written as the finalization/execution
  ACTION (\"a legal-risk clearance\", \"a source-verification sign-off\"),
  never as a bare noun (\"legal risk\", \"source verification\",
  \"sourcing\"). A bare-noun term would accidentally match inside this
  advisor's own legitimate `:flag-content-concern` rationale, which
  routinely discusses sourcing/legal-risk topics as OBSERVATIONS, not
  as finalized decisions -- see
  `pressops.advisor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  (:require [clojure.string :as str]
            [pressops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-production-record :schedule-production-operation
    :flag-content-concern :coordinate-distribution})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-content-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing an editorial-
  content decision, a legal-risk clearance decision, or a
  source-verification sign-off decision. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  Each term is phrased as the finalization/execution ACTION, not a
  bare noun -- a bare noun (e.g. \"sourcing\" or \"legal risk\") would
  self-trip on this actor's own legitimate `:flag-content-concern`
  proposals, which legitimately discuss sourcing-integrity and legal-
  risk topics as raw observations, never as finalized decisions."
  ["editorial-content decision" "editorial content decision"
   "finalize editorial content" "finalize the editorial content"
   "editorial decision" "編集内容の確定" "編集判断の確定" "編集決定"
   "legal-risk clearance" "legal risk clearance" "legal clearance"
   "defamation clearance" "libel clearance" "法的クリアランス" "法的リスクのクリアランス"
   "source-verification sign-off" "source verification sign-off"
   "sourcing sign-off" "source sign-off" "verification sign-off"
   "sign off on source verification" "sign off on sourcing"
   "情報源検証のサインオフ" "出典確認のサインオフ" "裏付け確認のサインオフ" "情報源確認の最終承認"])

;; ----------------------------- checks -----------------------------

(defn- publication-unverified-violations
  "The target publication must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:publication-id` claim without a store lookup."
  [{:keys [publication-id]} st]
  (let [r (store/publication st publication-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :publication-unverified
        :detail (str publication-id " は未登録または未検証の刊行物 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches finalizing-an-editorial-content-decision/
  legal-risk-clearance/source-verification-sign-off territory,
  regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "編集内容の確定判断/法的リスクのクリアランス判断/出典検証のサインオフ判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a PressAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [publication-id (or (:publication-id proposal) (:publication-id request))
        hard (into []
                   (concat (publication-unverified-violations {:publication-id publication-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t              :governor-hold
   :op             (:op request)
   :actor          (:actor-id context)
   :publication-id (:publication-id request)
   :disposition    :hold
   :basis          (mapv :rule (:violations verdict))
   :violations     (:violations verdict)
   :confidence     (:confidence verdict)})
