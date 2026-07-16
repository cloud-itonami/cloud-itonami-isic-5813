(ns pressops.governor-test
  "Pure unit tests of `pressops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [pressops.governor :as gov]
            [pressops.store :as store]))

(def pub-1 {:publication-id "pub-1" :name "The Daily Harbor Herald" :registered? true :verified? true})
(def pub-3 {:publication-id "pub-3" :name "Undisclosed Community Bulletin" :registered? true :verified? false})

(defn- clean-proposal [op publication-id]
  {:op op :publication-id publication-id :summary "s" :rationale "routine publishing coordination"
   :cites [publication-id] :effect :propose :value {} :confidence 0.85})

(deftest publication-unregistered-is-hard
  (testing "no publication record at all -> HARD hold"
    (let [s (store/mem-store {"pub-1" pub-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "unknown-pub") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:publication-unverified} (map :rule (:violations verdict)))))))

(deftest publication-unverified-is-hard
  (testing "publication registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"pub-3" pub-3})
          verdict (gov/check {} nil (clean-proposal :log-production-record "pub-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:publication-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"pub-1" pub-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-production-operation "pub-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"pub-1" pub-1})
          verdict (gov/check {} nil (clean-proposal :finalize-issue "pub-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest editorial-decision-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches finalizing an editorial-content decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"pub-1" pub-1})
          poisoned (assoc (clean-proposal :log-production-record "pub-1")
                          :rationale "finalized the editorial-content decision on the lead story's framing"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legal-risk-clearance-content-is-hard
  (testing "a proposal touching a legal-risk clearance decision is HARD-blocked, same as editorial-content"
    (let [s (store/mem-store {"pub-1" pub-1})
          poisoned (assoc (clean-proposal :log-production-record "pub-1")
                          :rationale "issued a legal-risk clearance for the defamation concern in the lead story"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest source-verification-signoff-content-is-hard
  (testing "a proposal touching a source-verification sign-off decision is HARD-blocked"
    (let [s (store/mem-store {"pub-1" pub-1})
          poisoned (assoc (clean-proposal :coordinate-distribution "pub-1")
                          :summary "issued a source-verification sign-off clearing the story for print")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest source-verification-signoff-value-content-is-hard
  (testing "a proposal touching a source-verification sign-off decision embedded in the draft value is HARD-blocked"
    (let [s (store/mem-store {"pub-1" pub-1})
          poisoned (assoc (clean-proposal :schedule-production-operation "pub-1")
                          :value {:decision "sourcing sign-off granted for the investigative feature"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-content-concern-is-not-scope-excluded
  (testing "flagging an observed defamation/sourcing-integrity/factual-accuracy concern (not a clearance or sign-off decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"pub-1" pub-1})
          concern (assoc (clean-proposal :flag-content-concern "pub-1")
                         :value {:concern "possible unverified factual claim about a named individual in the lead story, and the sourcing for that claim could not be independently confirmed"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (defamation/sourcing-integrity/factual-accuracy risk) is exactly what this op exists to surface"))))

(deftest legitimate-distribution-coordination-is-not-scope-excluded
  (testing "a clean distribution-coordination proposal that merely mentions it does not finalize content never trips scope-exclusion"
    (let [s (store/mem-store {"pub-1" pub-1})
          clean (assoc (clean-proposal :coordinate-distribution "pub-1")
                       :rationale "adjusts only the digital release schedule; does not finalize the story content")
          verdict (gov/check {} nil clean s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "scheduling language must not accidentally self-trip the source-verification-sign-off block"))))
