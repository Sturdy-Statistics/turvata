(ns turvata.ring.middleware-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ring.mock.request :as mock]
   [turvata.settings :as settings]
   [turvata.session :as sess]
   [turvata.ring.middleware :as mw]
   [turvata.test-support :as ts]))

;; A tiny protected handler that echoes the bound :user-id
(defn echo-user-id [req] {:status 200 :headers {} :body (:user-id req)})

(defn- make-env [ttl-ms]
  {:store (sess/in-memory-store)
   :settings (settings/normalize
              (merge ts/test-settings
                     {:session-ttl-ms ttl-ms
                      :cookie-name "test-cookie"
                      :login-url "/auth/login"}))})

(deftest web-auth-redirects-when-missing
  (let [env  (make-env 60000)
        app  ((mw/require-web-auth env) echo-user-id)
        resp (app (mock/request :get "/admin?x=1"))]
    (testing "Redirects to login with proper next param"
      (is (= 302 (:status resp)))
      (is (re-find #"/auth/login\?next=%2Fadmin%3Fx%3D1" (get-in resp [:headers "Location"]))))

    (testing "Aggressively clears the cookie and prevents caching"
      (is (= 0 (get-in resp [:cookies "test-cookie" :max-age])))
      (is (= "Thu, 01 Jan 1970 00:00:00 GMT" (get-in resp [:cookies "test-cookie" :expires])))
      (is (re-find #"no-store" (get-in resp [:headers "Cache-Control"])))
      (is (re-find #"Cookie" (get-in resp [:headers "Vary"]))))))

(deftest web-auth-success-no-refresh
  (let [env   (make-env 60000)
        base  (sess/now-ms)
        token "valid-tok"]
    (sess/put-entry! (:store env) token {:user-id "admin1" :expires-at (+ base 60000)})

    (let [app  ((mw/require-web-auth env) echo-user-id)
          req  (-> (mock/request :get "/admin")
                   (assoc :cookies {"test-cookie" {:value token}}))
          resp (app req)]
      (testing "Allows request and injects user-id"
        (is (= 200 (:status resp)))
        (is (= "admin1" (:body resp))))
      (testing "Does not mutate cookie if refresh isn't needed"
        (is (nil? (get-in resp [:cookies "test-cookie"])))))))

(deftest web-auth-refreshes-cookie-when-needed
  (let [env   (make-env 60000)
        token "refresh-tok"
        base  1000000]
    ;; Seed a session that is about to cross half TTL (30,000ms)
    (with-redefs [sess/now-ms (constantly base)]
      (sess/put-entry! (:store env) token {:user-id "alice" :expires-at (+ base 30100)}))

    (let [app  ((mw/require-web-auth env) echo-user-id)
          req  (-> (mock/request :get "/admin")
                   (mock/header :host "example.test")
                   (assoc :cookies {"test-cookie" {:value token}}))
          resp (with-redefs [sess/now-ms (constantly (+ base 30010))]
                 (app req))]
      (testing "Allows request"
        (is (= 200 (:status resp))))
      (testing "Issues a new cookie with updated max-age and Vary header"
        (is (some? (get-in resp [:cookies "test-cookie"])))
        (is (contains? (get-in resp [:cookies "test-cookie"]) :max-age))
        (is (re-find #"Cookie" (get-in resp [:headers "Vary"])))))))
