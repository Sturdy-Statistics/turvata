(ns turvata.catalog-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.catalog :as cat]
   [turvata.util :as u])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

(def uuid-a (random-uuid))
(def uuid-b (random-uuid))

(def record-a {:hash (byte-array 32) :rotation-version 1})
(def record-b {:hash (byte-array 32) :rotation-version 2})

(deftest in-memory-catalog-test
  (let [c (cat/in-memory-catalog {uuid-a record-a uuid-b record-b})]
    (is (= record-a (cat/lookup-record c uuid-a)))
    (is (= record-b (cat/lookup-record c uuid-b)))
    (is (nil?       (cat/lookup-record c (random-uuid))))))

(deftest fn-and-composite-test
  (let [c1 (cat/fn-catalog #(when (= % uuid-a) record-a))
        c2 (cat/in-memory-catalog {uuid-b record-b})
        c  (cat/composite [c1 c2 nil])]
    (is (= record-a (cat/lookup-record c uuid-a)))
    (is (= record-b (cat/lookup-record c uuid-b)))
    (is (nil?       (cat/lookup-record c (random-uuid))))))

(deftest edn-file-catalog-test
  (let [f (doto (File/createTempFile "tokens" ".edn")
            (.deleteOnExit))
        hex-hash (u/bytes->hex-string (byte-array 32))
        _ (spit f (pr-str [{:user-id uuid-a :hash-hex hex-hash :rotation-version 1}]))
        c (cat/edn-file-catalog (.getPath f))]

    (let [fetched (cat/lookup-record c uuid-a)]
      (is (= 1 (:rotation-version fetched)))
      ;; Proves the EDN catalog properly hydrated the hex string into bytes
      (is (bytes? (:hash fetched)))
      (is (u/bytes= (u/hex-string->bytes hex-hash) (:hash fetched))))

    (is (nil? (cat/lookup-record c uuid-b)))))

(deftest malformed-lookup-handling-test
  (testing "Catalogs safely return nil for non-UUID inputs instead of throwing exceptions"
    (let [c (cat/in-memory-catalog {uuid-a record-a})]
      (doseq [bad-input [nil "string-uuid" 123 :keyword [] {}]]
        (is (nil? (cat/lookup-record c bad-input)))))))

(deftest context-fn-catalog-test
  (testing "context-fn-catalog receives the context map"
    (let [c (cat/context-fn-catalog
             (fn [user-id ctx]
               (when (= user-id uuid-a)
                 (assoc record-a :request-ip (:request-ip ctx)))))]
      (is (= (assoc record-a :request-ip "192.168.1.1")
             (cat/lookup-record c uuid-a {:request-ip "192.168.1.1"})))
      (is (= (assoc record-a :request-ip nil)
             (cat/lookup-record c uuid-a)))
      (is (nil? (cat/lookup-record c "not-a-uuid" {:request-ip "192.168.1.1"})))))

  (testing "composite catalog propagates context down to context-aware children"
    (let [c1 (cat/context-fn-catalog
              (fn [user-id ctx]
                (when (and (= user-id uuid-a) (:audit? ctx))
                  record-a)))
          c2 (cat/in-memory-catalog {uuid-b record-b})
          c  (cat/composite [c1 c2])]
      (is (= record-a (cat/lookup-record c uuid-a {:audit? true})))
      (is (nil?      (cat/lookup-record c uuid-a {:audit? false})))
      (is (= record-b (cat/lookup-record c uuid-b {:audit? true}))))))
