(ns turvata.ring.logged-in-handler-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [ring.mock.request :as mock]
   [turvata.session  :as sess]
   [turvata.ring.handlers :as h]))

(def ^:dynamic *store* nil)

(use-fixtures :each
  (fn [f]
    (let [store (sess/in-memory-store)]
      (binding [*store*    store
                rt/*runtime* (rt/make-runtime {:settings {:session-ttl-ms 60000}
                                               :store    store})]
        (f)))))

(deftest logged-in-missing-cookie-returns-nil
  (let [resp     (h/logged-in?-handler (mock/request :get "/whoami"))]
    (is (nil? resp))))

(deftest logged-in-expired-cookie-returns-nil
  (let [cookie   (rt/settings [:cookie-name])
        token    "t1"
        base     1000000
        ;; seed expired entry
        _        (sess/put-entry! *store* token {:user-id "alice" :expires-at (dec base)})
        req      (assoc (mock/request :get "/whoami")
                        :cookies {cookie {:value token}})
        resp     (with-redefs [sess/now-ms (fn [] base)]
                   (h/logged-in?-handler req))]
    (is (nil? resp))))

(deftest logged-in-valid-cookie-no-refresh
  (let [ttl      (rt/settings [:session-ttl-ms])
        cookie   (rt/settings [:cookie-name])
        token    "t2"
        base     1000000
        exp      (+ base ttl)
        ;; seed valid far-from-expiry entry
        _        (with-redefs [sess/now-ms (fn [] base)]
                   (sess/put-entry! *store* token {:user-id "alice" :expires-at exp}))
        req      (assoc (mock/request :get "/whoami")
                        :cookies {cookie {:value token}})
        resp     (with-redefs [sess/now-ms (fn [] base)]
                   (h/logged-in?-handler req))]
    (is (= "alice" (:user-id resp)))
    (is (= exp      (:expires-at resp)))
    (is (false?     (:refreshed? resp)))))

(deftest logged-in-refreshes-when-half-or-less-remains
  (let [ttl      (rt/settings [:session-ttl-ms])
        cookie   (rt/settings [:cookie-name])
        token    "t3"
        base     1000000
        exp      (+ base (quot ttl 2))        ; half TTL remaining at base
        ;; seed entry
        _        (with-redefs [sess/now-ms (fn [] base)]
                   (sess/put-entry! *store* token {:user-id "alice" :expires-at exp}))
        ;; Call just before expiry, so remaining ≤ half TTL → should refresh
        now      (dec exp)
        req      (assoc (mock/request :get "/whoami")
                        :cookies {cookie {:value token}})
        resp     (with-redefs [sess/now-ms (fn [] now)]
                   (h/logged-in?-handler req))]
    (is (= "alice" (:user-id resp)))
    (is (true?     (:refreshed? resp)))
    (is (= (+ now ttl) (:expires-at resp)))
    ;; store got updated too
    (is (= (+ now ttl) (:expires-at (sess/get-entry *store* token))))))
