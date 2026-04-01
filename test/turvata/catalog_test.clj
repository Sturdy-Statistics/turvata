(ns turvata.catalog-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.catalog :as cat]
   [turvata.keys :as keys])
  (:import
   (java.io File)))

(deftest plain-map-catalog-test
  (let [c (cat/plain-map-catalog {"tokA" "alice" "tokB" :bob})]
    (is (= "alice" (cat/lookup-user-id c "tokA")))
    (is (= :bob    (cat/lookup-user-id c "tokB")))
    (is (nil?      (cat/lookup-user-id c "nope")))
    (is (true?     (cat/valid-token? c "tokA")))))

(deftest hashed-map-catalog-test
  (let [tok "secret-1"
        h   (keys/hash-token tok)
        c   (cat/hashed-map-catalog {h "alice"})]
    (is (= "alice" (cat/lookup-user-id c tok)))
    (is (nil? (cat/lookup-user-id c "wrong")))))

(deftest fn-and-composite-test
  (let [c1 (cat/fn-catalog #(when (= % "x") "user-x"))
        c2 (cat/plain-map-catalog {"y" "user-y"})
        c  (cat/composite [c1 c2 nil])] ;; Added a nil catalog to prove it safely removes it
    (is (= "user-x" (cat/lookup-user-id c "x")))
    (is (= "user-y" (cat/lookup-user-id c "y")))
    (is (nil?       (cat/lookup-user-id c "z")))))

(deftest edn-file-catalog-test
  (let [tok "t-123"
        h   (keys/hash-token tok)
        f   (doto (File/createTempFile "tokens" ".edn")
              (.deleteOnExit))
        _   (spit f (pr-str [{:hashed h :user-id "alice"}
                             {:hashed "other" :user-id "bob"}]))
        c   (cat/edn-file-catalog (.getPath f))]
    (is (= "alice" (cat/lookup-user-id c tok)))
    (is (nil? (cat/lookup-user-id c "nope")))))

(deftest malformed-token-handling-test
  (testing "Catalogs safely return nil for non-string/nil tokens instead of throwing exceptions"
    (let [c-plain (cat/plain-map-catalog {"123" "alice"})
          c-hash  (cat/hashed-map-catalog {(keys/hash-token "123") "alice"})
          c-comp  (cat/composite [c-plain c-hash])]
      (doseq [bad-input [nil 123 :keyword [] {}]]
        (is (nil? (cat/lookup-user-id c-plain bad-input)))
        (is (nil? (cat/lookup-user-id c-hash bad-input)))
        (is (nil? (cat/lookup-user-id c-comp bad-input)))))))

(deftest context-propagation-test
  (testing "fn-catalog accurately receives the context map"
    (let [c (cat/fn-catalog (fn [tok ctx]
                              (when (= tok "admin-tok")
                                (:request-ip ctx))))]
      (is (= "192.168.1.1" (cat/lookup-user-id c "admin-tok" {:request-ip "192.168.1.1"})))
      (is (nil? (cat/lookup-user-id c "admin-tok" nil)))))

  (testing "composite catalog correctly propagates the context down to its children"
    (let [c1 (cat/fn-catalog (fn [tok ctx]
                               (when (and (= tok "audit-tok") (:audit? ctx))
                                 "auditor")))
          c2 (cat/plain-map-catalog {"b" "user-b"}) ; Ignores context, but proves it doesn't crash
          c  (cat/composite [c1 c2])]
      ;; Proves c1 receives the context and acts on it
      (is (= "auditor" (cat/lookup-user-id c "audit-tok" {:audit? true})))
      (is (nil?        (cat/lookup-user-id c "audit-tok" {:audit? false})))
      ;; Proves c2 still works when called via the 2-arity method
      (is (= "user-b"  (cat/lookup-user-id c "b" {:audit? true}))))))
