(ns pressops.store-contract-test
  "Contract tests for `pressops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [pressops.store :as store]))

(deftest mem-store-publication-lookup
  (testing "MemStore can store and retrieve publications by ID (string keys)"
    (let [publications {"p1" {:publication-id "p1" :name "Alpha Gazette" :registered? true :verified? true}}
          s (store/mem-store publications)]
      (is (some? (store/publication s "p1")))
      (is (nil? (store/publication s "p99"))))))

(deftest mem-store-all-publications
  (testing "MemStore returns all publications in sorted order"
    (let [publications {"p2" {:publication-id "p2" :name "Bravo Bulletin"}
                        "p1" {:publication-id "p1" :name "Alpha Gazette"}
                        "p3" {:publication-id "p3" :name "Charlie Chronicle"}}
          s (store/mem-store publications)
          all-p (store/all-publications s)]
      (is (= 3 (count all-p)))
      (is (= "p1" (:publication-id (first all-p))))
      (is (= "p3" (:publication-id (last all-p)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-production-record :publication-id "p1" :value {:issue-status "final proof"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-publications
  (testing "MemStore with-publications replaces the publication directory"
    (let [s (store/mem-store {})
          new-publications {"p1" {:publication-id "p1" :name "Alpha Gazette"}}]
      (is (= 0 (count (store/all-publications s))))
      (store/with-publications s new-publications)
      (is (= 1 (count (store/all-publications s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo publications"
    (let [s (store/seed-db)]
      (is (> (count (store/all-publications s)) 0))
      (is (some? (store/publication s "pub-1")))
      (is (some? (store/publication s "pub-2")))
      (is (some? (store/publication s "pub-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for publication-id"
    (let [demo (store/demo-data)
          publications (:publications demo)]
      (doseq [[k v] publications]
        (is (string? k) "keys must be strings")
        (is (string? (:publication-id v)) "publication-id must be string")
        (is (= k (:publication-id v)) "key must match publication-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
