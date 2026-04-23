(ns turvata.session-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.session :as s]))

(set! *warn-on-reflection* true)

(deftest store-roundtrip-test
  (let [store (s/in-memory-store)
        tok   "t-1"
        e     {:user-id "alice" :expires-at (+ (s/now-ms) 60000)}]
    (is (nil? (s/get-entry store tok)))
    (s/put-entry! store tok e)
    (is (= e (s/get-entry store tok)))
    ;; delete returns the removed entry
    (is (= e (s/delete-entry! store tok)))
    (is (nil? (s/get-entry store tok)))))

(deftest touch-updates-existing-only-test
  (let [store (s/in-memory-store)
        tok   "t-2"
        t0    (s/now-ms)
        e     {:user-id "bob" :expires-at (+ t0 1000)}]
    ;; touching a missing token -> nil, no side effects
    (is (nil? (s/touch! store tok (+ t0 5000))))
    (is (nil? (s/get-entry store tok)))

    ;; put, then touch -> updated entry returned & stored
    (s/put-entry! store tok e)
    (let [t1 (+ t0 5000)
          touched (s/touch! store tok t1)]
      (is (= {:user-id "bob" :expires-at t1} touched))
      (is (= touched (s/get-entry store tok))))

    ;; delete, then touch again -> still nil
    (is (= {:user-id "bob" :expires-at (+ t0 5000)} (s/delete-entry! store tok)))
    (is (nil? (s/touch! store tok (+ t0 9000))))))

(deftest prune-expired-basic-test
  (let [store (s/in-memory-store)
        base  (s/now-ms)
        alive {"a" {:user-id "a" :expires-at (+ base 10)}
               "b" {:user-id "b" :expires-at (+ base 99999)}}
        dead  {"x" {:user-id "x" :expires-at (- base 1)}
               "y" {:user-id "y" :expires-at (- base 12345)}}]
    (doseq [[tok e] (merge alive dead)]
      (s/put-entry! store tok e))

    ;; prune should return the number removed
    (is (= (count dead) (s/prune-expired! store base)))

    ;; only alive remain
    (is (= (set (keys alive))
           (->> ["a" "b" "x" "y"]
                (filter #(some? (s/get-entry store %)))
                set)))
    (is (= (into {} alive)
           (into {} (for [k (keys alive)] [k (s/get-entry store k)]))))
    (is (nil? (s/get-entry store "x")))
    (is (nil? (s/get-entry store "y")))))

(deftest idempotent-ops-test
  (let [store (s/in-memory-store)
        tok   "t-3"
        t0    (s/now-ms)]
    ;; delete missing -> nil
    (is (nil? (s/delete-entry! store tok)))
    ;; prune on empty -> 0
    (is (zero? (s/prune-expired! store t0)))
    ;; put twice -> last wins
    (s/put-entry! store tok {:user-id "u" :expires-at (+ t0 10)})
    (s/put-entry! store tok {:user-id "u" :expires-at (+ t0 20)})
    (is (= (+ t0 20) (:expires-at (s/get-entry store tok))))))

(deftest authenticate-browser-token-refresh-test
  (let [base  1000000
        tok   "t"
        ttl   3600000 ;; 1 hour
        half  (quot ttl 2)
        store (s/in-memory-store)
        env   {:store store :settings {:session-ttl-ms ttl}}]
    (with-redefs [s/now-ms (constantly base)]
      (s/put-entry! store tok {:user-id "alice" :expires-at (+ base ttl)})

      (testing "more than half remaining -> no refresh"
        (with-redefs [s/now-ms (constantly (+ base (dec half)))]  ;; just before half
          (is (= {:user-id "alice" :refreshed? false :expires-at (+ base ttl)}
                 (s/authenticate-browser-token env tok)))))

      (testing "half or less remaining -> refresh"
        (with-redefs [s/now-ms (constantly (+ base half 1))]      ;; just past half
          (let [now (s/now-ms)
                out (s/authenticate-browser-token env tok)]
            (is (:refreshed? out))
            ;; refreshed expiry should be recomputed from "now"
            (is (= (+ now ttl) (:expires-at out)))))))))

(deftest authenticate-browser-token-edge-cases-test
  (let [store (s/in-memory-store)
        env   {:store store :settings {:session-ttl-ms 60000}}]

    (testing "Fails closed on missing or blank tokens"
      (is (nil? (s/authenticate-browser-token env nil)))
      (is (nil? (s/authenticate-browser-token env "")))
      (is (nil? (s/authenticate-browser-token env "   "))))

    (testing "Fails closed on unknown tokens"
      (is (nil? (s/authenticate-browser-token env "does-not-exist"))))

    (testing "Fails closed on strictly expired tokens"
      (let [base (s/now-ms)]
        ;; Insert a token that expired 1ms ago
        (s/put-entry! store "expired-tok" {:user-id "bob" :expires-at (- base 1)})
        (with-redefs [s/now-ms (constantly base)]
          (is (nil? (s/authenticate-browser-token env "expired-tok"))))))))
