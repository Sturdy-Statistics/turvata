(ns turvata.catalog-test
  (:require
   [clojure.test :refer [deftest is]]
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
        c  (cat/composite [c1 c2])]
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
