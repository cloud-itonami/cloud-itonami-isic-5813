# Contributing to cloud-itonami-isic-5813

Contributions should preserve the actor's scope: back-office coordination only,
with CRITICAL exclusions of finalizing editorial-content decisions,
legal-risk clearance decisions, and source-verification sign-off decisions
(see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize an editorial-content decision (what a story/issue actually says, whether it runs as written).
- Issue a legal-risk clearance decision (defamation/libel risk sign-off).
- Issue a source-verification sign-off decision (declaring sourcing/fact-checking of a story complete and cleared).

Contributions that cross these boundaries will be rejected.
