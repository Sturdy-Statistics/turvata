(ns turvata.ring.logged-in-handler-test
  (:require
   [clojure.test :refer [deftest is]]
   [ring.mock.request :as mock]
   [turvata.settings :as settings]
   [turvata.session  :as sess]
   [turvata.ring.handlers :as h]
   [turvata.test-support :as ts]))

(defn- make-env []
  {:store (sess/in-memory-store)
   :settings (settings/normalize
              (merge ts/test-settings
                     {:session-ttl-ms 60000
                      :cookie-name "test-cookie"}))})

(deftest logged-in-missing-cookie-returns-nil
  (let [env  (make-env)
        resp ((h/make-logged-in?-handler env) (mock/request :get "/whoami"))]
    (is (nil? resp))))

(deftest logged-in-expired-cookie-returns-nil
  (let [env   (make-env)
        token "t1"
        base  1000000]
    ;; seed expired entry
    (sess/put-entry! (:store env) token {:user-id "alice" :expires-at (dec base)})

    (let [req  (assoc (mock/request :get "/whoami")
                      :cookies {"test-cookie" {:value token}})
          resp (with-redefs [sess/now-ms (constantly base)]
                 ((h/make-logged-in?-handler env) req))]
      (is (nil? resp)))))

(deftest logged-in-valid-cookie-no-refresh
  (let [env   (make-env)
        token "t2"
        base  1000000
        exp   (+ base 60000)]
    ;; seed valid far-from-expiry entry
    (with-redefs [sess/now-ms (constantly base)]
      (sess/put-entry! (:store env) token {:user-id "alice" :expires-at exp}))

    (let [req  (assoc (mock/request :get "/whoami")
                      :cookies {"test-cookie" {:value token}})
          resp (with-redefs [sess/now-ms (constantly base)]
                 ((h/make-logged-in?-handler env) req))]
      (is (= "alice" (:user-id resp)))
      (is (= exp     (:expires-at resp)))
      (is (false?    (:refreshed? resp))))))

(deftest logged-in-refreshes-when-half-or-less-remains
  (let [env   (make-env)
        token "t3"
        base  1000000
        exp   (+ base 30000)]  ; half TTL remaining at base
    ;; seed entry
    (with-redefs [sess/now-ms (constantly base)]
      (sess/put-entry! (:store env) token {:user-id "alice" :expires-at exp}))

    ;; Call just before expiry, so remaining <= half TTL -> should refresh
    (let [now  (dec exp)
          req  (assoc (mock/request :get "/whoami")
                      :cookies {"test-cookie" {:value token}})
          resp (with-redefs [sess/now-ms (constantly now)]
                 ((h/make-logged-in?-handler env) req))]
      (is (= "alice" (:user-id resp)))
      (is (true?     (:refreshed? resp)))
      (is (= (+ now 60000) (:expires-at resp)))
      ;; store got updated too
      (is (= (+ now 60000) (:expires-at (sess/get-entry (:store env) token)))))))
