(ns pressops.advisor-test
  "Unit tests of `pressops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [pressops.advisor :as adv]
            [pressops.governor :as gov]
            [pressops.store :as store]))

(def db (store/seed-db))

(deftest propose-production-record-shape
  (testing "production-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-production-record
                           :publication-id "pub-1"
                           :patch {:issue-status "final proof" :print-run 12000}})]
      (is (= :log-production-record (:op p)))
      (is (= "pub-1" (:publication-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :publication-id)))))

(deftest propose-production-schedule-shape
  (testing "production-operation scheduling proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-production-operation
                           :publication-id "pub-2"
                           :patch {:stage "layout" :date "2026-08-01"}})]
      (is (= :schedule-production-operation (:op p)))
      (is (= "pub-2" (:publication-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-distribution-coordination-shape
  (testing "distribution-coordination proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-distribution
                           :publication-id "pub-1"
                           :patch {:channel "digital" :release-date "2026-08-03"}})]
      (is (= :coordinate-distribution (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-content-concern-shape
  (testing "content-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-content-concern
                           :publication-id "pub-1"
                           :patch {:concern "possible defamation risk in the lead story"}})]
      (is (= :flag-content-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-distribution :flag-content-concern]]
      (let [p (adv/infer db {:op op :publication-id "pub-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-distribution :flag-content-concern]]
      (let [p (adv/infer db {:op op :publication-id "pub-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every op's default (clean, in-scope) mock-advisor proposal must clear the governor's scope-exclusion check on its own generated text -- a proposal must never accidentally describe itself using the very terms that would make it permanently blocked"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-distribution :flag-content-concern]]
      (let [p (adv/infer db {:op op :publication-id "pub-1"
                             :patch {:concern "possible unverified source flagged for review"}})
            s (store/mem-store {"pub-1" {:publication-id "pub-1" :name "The Daily Harbor Herald"
                                          :registered? true :verified? true}})
            verdict (gov/check {:publication-id "pub-1"} nil p s)]
        (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
            (str "op " op "'s own default proposal text must not self-trip scope-exclusion"))))))

(deftest out-of-scope-hook-trips-scope-exclusion
  (testing "the test-only :out-of-scope? hook produces text the governor correctly HARD-blocks"
    (let [p (adv/infer db {:op :log-production-record :publication-id "pub-1"
                           :out-of-scope? true :patch {}})
          s (store/mem-store {"pub-1" {:publication-id "pub-1" :name "The Daily Harbor Herald"
                                        :registered? true :verified? true}})
          verdict (gov/check {:publication-id "pub-1"} nil p s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))
