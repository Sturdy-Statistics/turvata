(ns turvata.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.core :as core]
   [turvata.crypto :as crypto]
   [turvata.test-support :as ts])
  (:import
   (java.time Instant)))

(set! *warn-on-reflection* true)

(deftest verify-key-test
  (let [pepper  ts/test-pepper-bytes
        uuid    (random-uuid)
        secret  (byte-array 32)
        tok-map {:user-id uuid :rotation-version 2 :secret secret}

        ;; Compute what the correct hash should be
        valid-hash (crypto/hash-key pepper tok-map)

        now     (Instant/now)
        future  (.plusSeconds now 3600)
        past    (.minusSeconds now 3600)]

    (testing "Primary Match Success"
      (let [db-row {:hash valid-hash :rotation-version 2}]
        (is (= :valid/primary (core/verify-key pepper tok-map db-row now)))))

    (testing "Primary Match Fails if Expired"
      (let [db-row {:hash valid-hash :rotation-version 2 :expires-at past}]
        (is (false? (core/verify-key pepper tok-map db-row now)))))

    (testing "Primary Match Fails on Bad Hash"
      (let [db-row {:hash (byte-array 64) :rotation-version 2}]
        (is (false? (core/verify-key pepper tok-map db-row now)))))

    (testing "Rollback Resistance: Fails if Token Version matches DB Version but Hash is old"
      (let [db-row {:hash (byte-array 64) :rotation-version 2 :prev-hash valid-hash}]
        (is (false? (core/verify-key pepper tok-map db-row now)))))

    (testing "Zero-Downtime Rotation: Grace Match Success"
      (let [ ;; Token is V2 (from tok-map), DB is V3
            db-row {:hash (byte-array 64)
                    :rotation-version 3
                    :prev-hash valid-hash
                    :grace-period-expires-at future}]
        (is (= :valid/grace (core/verify-key pepper tok-map db-row now)))))

    (testing "Zero-Downtime Rotation: Grace Match Fails if Grace Period Expired"
      (let [db-row {:hash (byte-array 64)
                    :rotation-version 3
                    :prev-hash valid-hash
                    :grace-period-expires-at past}]
        (is (false? (core/verify-key pepper tok-map db-row now)))))))
