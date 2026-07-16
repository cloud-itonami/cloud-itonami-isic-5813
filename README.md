# cloud-itonami-isic-5813

**Publishing of newspapers, journals and periodicals** — ISIC Rev.4 class 5813.

A coordination-only actor for newspaper/journal/periodical publishing back-office operations, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-production-record, schedule-production-operation, coordinate-distribution, flag-content-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Publication verified** — target publication (a newspaper/journal/periodical masthead) must exist AND be registered/verified in the store before any proposal for it may commit or escalate.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing an editorial-content decision, a legal-risk clearance decision, and a source-verification sign-off decision are permanently blocked, regardless of confidence or op.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: production-record logging only (approval-gated)
  - Phase 2: + production-operation scheduling, distribution coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (content concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL scope exclusions

This actor coordinates the back-office operations of a newspaper/journal/
periodical publishing house: issue/edition/print-run production-record
logging, editing/layout/print-run scheduling proposals, outbound
distribution/circulation coordination, and content-risk-concern flagging
(defamation, sourcing-integrity, factual-accuracy).

**This actor does NOT:**
- Finalize an editorial-content decision (what a story/issue actually says, whether it runs as written).
- Issue a legal-risk clearance decision (defamation/libel risk sign-off).
- Issue a source-verification sign-off decision (declaring sourcing/fact-checking of a story complete and cleared).

Every proposal is `:effect :propose` only. `:flag-content-concern` always
escalates to a human, at every phase, regardless of confidence — this
actor never self-clears a content-risk concern it raises.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/pressops/governor_test.clj` — unit tests of governor hard checks and scope exclusion
- `test/pressops/advisor_test.clj` — advisor proposal shape and consistency
- `test/pressops/phase_test.clj` — rollout phase logic
- `test/pressops/governor_contract_test.clj` — full graph integration, audit trail
- `test/pressops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `pressops.store` — SSoT (MemStore, String-keyed publication directory, append-only ledger)
- `pressops.advisor` — contained intelligence node (mock + real-LLM seam)
- `pressops.governor` — independent compliance layer
- `pressops.phase` — staged rollout (0→3)
- `pressops.operation` — langgraph-clj StateGraph
- `pressops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-facing/personal-services) fleet. See ADR-2607121000, ADR-2607152500, and the ISIC-5813 coverage ADR for design decisions.
