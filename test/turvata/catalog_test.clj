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

;; (deftest context-propagation-test
;;   (testing "fn-catalog accurately receives the context map"
;;     (let [c (cat/fn-catalog (fn [tok ctx]
;;                               (when (= tok "admin-tok")
;;                                 (:request-ip ctx))))]
;;       (is (= "192.168.1.1" (cat/lookup-user-id c "admin-tok" {:request-ip "192.168.1.1"})))
;;       (is (nil? (cat/lookup-user-id c "admin-tok" nil)))))

;; (testing "composite catalog correctly propagates the context down to its children"
;;   (let [c1 (cat/fn-catalog (fn [tok ctx]
;;                              (when (and (= tok "audit-tok") (:audit? ctx))
;;                                "auditor")))
;;         c2 (cat/in-memory-catalog {uuid-a record-a uuid-b record-b}) ; Ignores context, but proves it doesn't crash
;;         c  (cat/composite [c1 c2])]
;;     ;; Proves c1 receives the context and acts on it
;;     (is (= "auditor" (cat/lookup-user-id c "audit-tok" {:audit? true})))
;;     (is (nil?        (cat/lookup-user-id c "audit-tok" {:audit? false})))
;;     ;; Proves c2 still works when called via the 2-arity method
;;     (is (= "user-b"  (cat/lookup-user-id c "b" {:audit? true}))))))
