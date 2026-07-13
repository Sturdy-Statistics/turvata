(ns turvata.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.catalog :as cat]
   [turvata.codec :as codec]
   [turvata.core :as core]
   [turvata.crypto :as crypto]
   [turvata.settings :as settings]
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

(deftest verify-key-supports-unsigned-16-bit-rotation-versions-test
  (doseq [rotation-version [32767 32768 65535]]
    (testing (str "rotation-version " rotation-version)
      (let [user-id   (random-uuid)
            token!!   (codec/generate-token!! {:prefix "sturdy-test"
                                               :rotation-version rotation-version
                                               :user-id user-id})
            token-map (codec/parse-token!! token!!)
            hash!!    (crypto/hash-key ts/test-pepper-bytes token-map)
            db-row    {:hash hash!! :rotation-version rotation-version}]
        (is (= :valid/primary
               (core/verify-key ts/test-pepper-bytes token-map db-row (Instant/now))))))))

(deftest authenticate-api-token-rejects-prefix-mismatch-test
  (let [user-id   (random-uuid)
        token!!   (codec/generate-token!! {:prefix "other-svc"
                                           :rotation-version 1
                                           :user-id user-id})
        token-map (codec/parse-token!! token!!)
        catalog   (cat/in-memory-catalog
                   {user-id {:hash (crypto/hash-key ts/test-pepper-bytes token-map)
                             :rotation-version 1}})
        env       {:catalog catalog
                   :settings (settings/normalize ts/test-settings)}]
    (is (nil? (core/authenticate-api-token token!! env {})))))

(deftest authenticate-api-token-skips-catalog-on-prefix-mismatch-test
  (let [catalog-called? (atom false)
        token!!         (codec/generate-token!! {:prefix "other-svc"
                                                 :rotation-version 1
                                                 :user-id (random-uuid)})
        catalog         (reify cat/TokenCatalog
                          (lookup-record [_ _user-id]
                            (reset! catalog-called? true)
                            nil)
                          (lookup-record [_ _user-id _request]
                            (reset! catalog-called? true)
                            nil))
        env             {:catalog catalog
                         :settings (settings/normalize ts/test-settings)}]
    (is (nil? (core/authenticate-api-token token!! env {})))
    (is (false? @catalog-called?))))

(deftest authenticate-api-token-supports-one-arg-fn-catalog-test
  (let [user-id   (random-uuid)
        token!!   (codec/generate-token!! {:prefix "sturdy-test"
                                           :rotation-version 1
                                           :user-id user-id})
        token-map (codec/parse-token!! token!!)
        db-row    {:hash (crypto/hash-key ts/test-pepper-bytes token-map)
                   :rotation-version 1}
        catalog   (cat/fn-catalog #(when (= % user-id) db-row))
        env       {:catalog catalog
                   :settings (settings/normalize ts/test-settings)}]
    (is (= user-id (core/authenticate-api-token token!! env {:request-id "req-1"})))))

(deftest authenticate-api-token-error-classification-test
  (let [user-id   (random-uuid)
        token!!   (codec/generate-token!! {:prefix "sturdy-test"
                                           :rotation-version 1
                                           :user-id user-id})
        settings  (settings/normalize ts/test-settings)]
    (testing "malformed credentials are ordinary authentication failures"
      (is (nil? (core/authenticate-api-token "malformed" {:settings settings} {}))))

    (testing "catalog failures propagate to the application's error boundary"
      (let [catalog (cat/context-fn-catalog
                     (fn [_user-id _request]
                       (throw (ex-info "catalog unavailable" {:component :catalog}))))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"catalog unavailable"
                              (core/authenticate-api-token
                               token!! {:catalog catalog :settings settings} {})))))

    (testing "malformed catalog rows propagate instead of becoming invalid credentials"
      (let [catalog (cat/in-memory-catalog {user-id {:hash (byte-array 64)}})]
        (is (thrown? Exception
                     (core/authenticate-api-token
                      token!! {:catalog catalog :settings settings} {})))))))
