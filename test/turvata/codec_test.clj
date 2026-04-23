(ns turvata.codec-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.codec :as codec]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(deftest generate-and-parse-roundtrip-test
  (testing "A generated token perfectly parses back to its structural inputs"
    (let [uuid     (random-uuid)
          input    {:prefix "test-svc" :rotation-version 5 :user-id uuid}
          token!!  (codec/generate-token!! input)
          parsed   (codec/parse-token!! token!!)]

      (is (= "test-svc" (:prefix parsed)))
      (is (= 2 (:spec-version parsed)))
      (is (= 5 (:rotation-version parsed)))
      (is (= uuid (:user-id parsed)))
      (is (bytes? (:secret parsed)))
      (is (= 32 (alength ^bytes (:secret parsed)))))))

(deftest parsing-normalization-test
  (testing "Base32 parsing is case-insensitive but whitespace strict"
    (let [uuid    (random-uuid)
          token!! (codec/generate-token!! {:prefix "svc" :rotation-version 1 :user-id uuid})]
      (is (= uuid (:user-id (codec/parse-token!! (str/upper-case token!!)))))
      (is (thrown? Exception (codec/parse-token!! (str " " token!!)))))))

(deftest parsing-security-rejections-test
  (testing "Fails fast on bad checksums, bad versions, or malformed strings"
    (let [valid-token (codec/generate-token!! {:prefix "a" :rotation-version 1 :user-id (random-uuid)})

          parts       (clojure.string/split valid-token #"_")
          payload     (nth parts 2)
          bad-char    (if (= \a (first payload)) "b" "a")
          corrupted   (str (nth parts 0) "_" (nth parts 1) "_" bad-char (subs payload 1))]

      (is (thrown? Exception (codec/parse-token!! corrupted)))
      (is (thrown? Exception (codec/parse-token!! "not_enough_parts")))
      (is (thrown? Exception (codec/parse-token!! "prefix_010001_VALIDBASE32BUTWRONGVERSION"))))))
