(ns turvata.ring.login-test
  (:require
   [clojure.test :refer [deftest is]]
   [ring.mock.request :as mock]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.codec :as rcodec]
   [turvata.catalog :as cat]
   [turvata.session :as sess]
   [turvata.settings :as settings]
   [turvata.test-support :as ts]
   [turvata.codec :as codec]
   [turvata.crypto :as crypto]
   [turvata.ring.handlers :as h]))

(set! *warn-on-reflection* true)

(def test-uuid (random-uuid))
(def test-token-str
  (codec/generate-token!! {:prefix "sturdy-test" :rotation-version 1 :user-id test-uuid}))
(def test-parsed (codec/parse-token!! test-token-str))
(def test-valid-hash (crypto/hash-key ts/test-pepper-bytes test-parsed))

(def catalog
  (reify cat/TokenCatalog
    (lookup-record [_ user-id-uuid _req]
      (when (= user-id-uuid test-uuid)
        {:hash test-valid-hash :rotation-version 1}))))

(defn- make-env []
  {:store (sess/in-memory-store)
   :catalog catalog
   :settings (settings/normalize
              (merge ts/test-settings
                     {:session-ttl-ms 60000
                      :post-login-redirect "/admin"
                      :cookie-name "test-cookie"
                      :login-url "/auth/login"}))})

(deftest login-success-sets-cookie-and-redirects
  (let [env  (make-env)
        app  (-> (h/make-login-handler env) wrap-params)
        req  (-> (mock/request :post "/auth/login")
                 (mock/header "content-type" "application/x-www-form-urlencoded")
                 (mock/body (rcodec/form-encode
                             {"username" (str test-uuid)
                              "token"    test-token-str})))
        resp (app req)]
    (is (= 303 (:status resp)))
    (is (= "/admin" (get-in resp [:headers "Location"])))
    (is (= test-uuid (:user-id (sess/get-entry (:store env) (get-in resp [:cookies "test-cookie" :value])))))
    (is (pos? (get-in resp [:cookies "test-cookie" :max-age])))))

(deftest login-bad-credentials-redirects-to-login
  (let [env     (make-env)
        handler (h/make-login-handler env)
        ;; valid format, but it doesn't exist in the mock DB
        bad-tok (codec/generate-token!! {:prefix "sturdy-test" :rotation-version 1 :user-id (random-uuid)})
        request (-> (mock/request :post "/auth/login")
                    (assoc :params {"username" (str test-uuid) "token" bad-tok}))
        resp    (handler request)]

    (is (= 303 (:status resp)))
    (is (re-find #"/auth/login\?error=bad_credentials" (get-in resp [:headers "Location"])))))

(deftest login-preserves-next-on-success
  (let [env  (make-env)
        app  (-> (h/make-login-handler env) wrap-params)
        req  (-> (mock/request :post "/auth/login")
                 (mock/header "content-type" "application/x-www-form-urlencoded")
                 (mock/body (rcodec/form-encode
                             {"username" (str test-uuid)
                              "token"    test-token-str
                              "next"     "/reports?page=2"})))
        resp (app req)]
    (is (= 303 (:status resp)))
    (is (= "/reports?page=2" (get-in resp [:headers "Location"])))))

(deftest already-logged-in-returns-303
  (let [env     (make-env)
        handler (h/make-login-handler env)
        token   "sess1"
        now     (System/currentTimeMillis)]
    (sess/put-entry! (:store env) token {:user-id "alice" :expires-at (+ now 60000)})

    (let [req  (-> (mock/request :post "/auth/login")
                   (assoc :params {"username" test-uuid "token" test-token-str})
                   (assoc :cookies {"test-cookie" {:value token}}))
          resp (handler req)]
      (is (= 303 (:status resp)))
      (is (= "/admin" (get-in resp [:headers "Location"]))))))

(deftest login-blocks-malicious-redirects
  (let [env     (make-env)
        handler (h/make-login-handler env)
        req     (-> (mock/request :post "/auth/login")
                    (assoc :params {"username" (str test-uuid)
                                    "token"    test-token-str
                                    "next"     "https://evil-phishing-site.com"}))
        resp    (handler req)]
    (is (= 400 (:status resp)) "Should block external redirects")
    (is (get-in resp [:body :details :next]) "Should report error for the 'next' field")))

(deftest login-security-no-keyword-leak
  (let [env      (make-env)
        app      (-> (h/make-login-handler env) wrap-params)
        junk-key (str "attack-" (random-uuid))
        req      (-> (mock/request :post "/auth/login")
                     (mock/header "content-type" "application/x-www-form-urlencoded")
                     (mock/body (rcodec/form-encode
                                 {junk-key   "val"
                                  "username" (str test-uuid)
                                  "token"    test-token-str})))
        resp     (app req)]

    ;; Malli-firewall coerces the string keys and strips the unknown 'junk-key'
    (is (= 303 (:status resp)))
    (is (= "/admin" (get-in resp [:headers "Location"])))

    ;; malicious key was ignored and never interned!
    (is (nil? (find-keyword junk-key))
        "Unknown keys must never be interned as keywords.")))
