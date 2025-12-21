(ns turvata.session-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [turvata.core :refer [authenticate-browser-token]]
   [turvata.test-support :as ts]
   [turvata.session :as s]))

(def ^:dynamic *store* nil)

(use-fixtures :each
  (fn [f]
    (let [store (s/in-memory-store)]
      (binding [*store*    store
                rt/*runtime* (rt/make-runtime {:settings ts/test-settings
                                               :store    store})]
        (f)))))


(deftest store-roundtrip-test
  (let [tok   "t-1"
        e     {:user-id "alice" :expires-at (+ (s/now-ms) 60000)}]
    (is (nil? (s/get-entry *store* tok)))
    (s/put-entry! *store* tok e)
    (is (= e (s/get-entry *store* tok)))
    ;; delete returns the removed entry
    (is (= e (s/delete-entry! *store* tok)))
    (is (nil? (s/get-entry *store* tok)))))

(deftest touch-updates-existing-only-test
  (let [tok   "t-2"
        t0    (s/now-ms)
        e     {:user-id "bob" :expires-at (+ t0 1000)}]
    ;; touching a missing token -> nil, no side effects
    (is (nil? (s/touch! *store* tok (+ t0 5000))))
    (is (nil? (s/get-entry *store* tok)))

    ;; put, then touch -> updated entry returned & stored
    (s/put-entry! *store* tok e)
    (let [t1 (+ t0 5000)
          touched (s/touch! *store* tok t1)]
      (is (= {:user-id "bob" :expires-at t1} touched))
      (is (= touched (s/get-entry *store* tok))))

    ;; delete, then touch again -> still nil
    (is (= {:user-id "bob" :expires-at (+ t0 5000)} (s/delete-entry! *store* tok)))
    (is (nil? (s/touch! *store* tok (+ t0 9000))))))

(deftest prune-expired-basic-test
  (let [base  (s/now-ms)
        alive {"a" {:user-id "a" :expires-at (+ base 10)}
               "b" {:user-id "b" :expires-at (+ base 99999)}}
        dead  {"x" {:user-id "x" :expires-at (- base 1)}
               "y" {:user-id "y" :expires-at (- base 12345)}}]
    (doseq [[tok e] (merge alive dead)]
      (s/put-entry! *store* tok e))

    ;; prune should return the number removed
    (is (= (count dead) (s/prune-expired! *store* base)))

    ;; only alive remain
    (is (= (set (keys alive))
           (->> ["a" "b" "x" "y"]
                (filter #(some? (s/get-entry *store* %)))
                set)))
    (is (= (into {} alive)
           (into {} (for [k (keys alive)] [k (s/get-entry *store* k)]))))
    (is (nil? (s/get-entry *store* "x")))
    (is (nil? (s/get-entry *store* "y")))))

(deftest idempotent-ops-test
  (let [tok   "t-3"
        t0    (s/now-ms)]
    ;; delete missing → nil
    (is (nil? (s/delete-entry! *store* tok)))
    ;; prune on empty → 0
    (is (zero? (s/prune-expired! *store* t0)))
    ;; put twice → last wins
    (s/put-entry! *store* tok {:user-id "u" :expires-at (+ t0 10)})
    (s/put-entry! *store* tok {:user-id "u" :expires-at (+ t0 20)})
    (is (= (+ t0 20) (:expires-at (s/get-entry *store* tok))))))

(deftest authenticate-browser-token-refresh-test
  (let [base  1000000
        tok   "t"
        ttl   (long (rt/settings [:session-ttl-ms]))
        half  (quot ttl 2)]
    (with-redefs [s/now-ms (fn [] base)]
      (s/put-entry! *store* tok {:user-id "alice" :expires-at (+ base ttl)})

      ;; more than half remaining → no refresh
      (with-redefs [s/now-ms (fn [] (+ base (dec half)))]  ;; just before half
        (is (= {:user-id "alice" :refreshed? false :expires-at (+ base ttl)}
               (select-keys (authenticate-browser-token tok)
                            [:user-id :refreshed? :expires-at]))))

      ;; half or less remaining → refresh
      (with-redefs [s/now-ms (fn [] (+ base half 1))]      ;; just past half
        (let [now (s/now-ms)
              out (authenticate-browser-token tok)]
          (is (:refreshed? out))
          ;; refreshed expiry should be recomputed from "now"
          (is (= (+ now ttl) (:expires-at out))))))))
