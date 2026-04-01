(ns turvata.ring.login-test
  (:require
   [clojure.test :refer [deftest is]]
   [ring.mock.request :as mock]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.codec :as rcodec]          ;; for form encoding
   [turvata.catalog :as cat]
   [turvata.session :as sess]
   [turvata.settings :as settings]
   [turvata.ring.handlers :as h]))

(def catalog
  (reify cat/TokenCatalog
    (lookup-user-id [_ token _req] ({"good" "alice"} token))))

(defn- make-env []
  {:store (sess/in-memory-store)
   :catalog catalog
   :settings (settings/normalize {:session-ttl-ms 60000
                                  :post-login-redirect "/admin"
                                  :cookie-name "test-cookie"
                                  :login-url "/auth/login"})})

(deftest login-success-sets-cookie-and-redirects
  (let [env  (make-env)
        app  (-> (h/make-login-handler env)
                 wrap-params)
        req  (-> (mock/request :post "/auth/login")
                 (mock/header "content-type" "application/x-www-form-urlencoded")
                 (mock/body (rcodec/form-encode
                             {"username" "alice"
                              "token" "good"})))
        resp (app req)]
    (is (= 303 (:status resp)))
    (is (= "/admin" (get-in resp [:headers "Location"])))
    (is (= "alice" (:user-id (sess/get-entry (:store env) (get-in resp [:cookies "test-cookie" :value])))))
    (is (pos? (get-in resp [:cookies "test-cookie" :max-age])))))

(deftest login-bad-credentials-redirects-to-login
  (let [env     (make-env)
        handler (h/make-login-handler env)
        request (-> (mock/request :post "/auth/login")
                    (assoc :params {"username" "alice" "token" "nope"}))
        resp    (handler request)]

    (is (= 303 (:status resp)))
    (is (re-find #"/auth/login\?error=bad_credentials" (get-in resp [:headers "Location"])))))

(deftest login-preserves-next-on-success
  (let [env  (make-env)
        app  (-> (h/make-login-handler env)
                 wrap-params)
        req  (-> (mock/request :post "/auth/login")
                 (mock/header "content-type" "application/x-www-form-urlencoded")
                 (mock/body (rcodec/form-encode
                             {"username" "alice"
                              "token"    "good"
                              "next"     "/reports?page=2"})))
        resp (app req)]
    (is (= 303 (:status resp)))
    (is (= "/reports?page=2" (get-in resp [:headers "Location"])))))

(deftest already-logged-in-returns-303
  (let [env     (make-env)
        handler (h/make-login-handler env)
        token   "sess1"
        now     (System/currentTimeMillis)]
    ;; seed a valid session cookie
    (sess/put-entry! (:store env) token {:user-id "alice" :expires-at (+ now 60000)})

    (let [req  (-> (mock/request :post "/auth/login")
                   (assoc :params {"username" "alice" "token" "good"})
                   (assoc :cookies {"test-cookie" {:value token}}))
          resp (handler req)]
      (is (= 303 (:status resp)))
      ;; Because of our UX fix, it should redirect to the safe post-login destination
      (is (= "/admin" (get-in resp [:headers "Location"]))))))

(deftest login-blocks-malicious-redirects
  (let [env     (make-env)
        handler (h/make-login-handler env)
        ;; Malicious next parameter
        req     (-> (mock/request :post "/auth/login")
                    (assoc :params {"username" "alice"
                                    "token"    "good"
                                    "next"     "https://evil-phishing-site.com"}))
        resp    (handler req)]
    ;; The firewall should catch this because https://... isn't a RelativeURI
    (is (= 400 (:status resp)) "Should block external redirects")
    (is (get-in resp [:body :details :next]) "Should report error for the 'next' field")))

(deftest login-security-no-keyword-leak
  (let [env      (make-env)
        app      (-> (h/make-login-handler env)
                     wrap-params)
        junk-key (str "attack-" (random-uuid))
        req      (-> (mock/request :post "/auth/login")
                     (mock/header "content-type" "application/x-www-form-urlencoded")
                     (mock/body (rcodec/form-encode
                                 {junk-key   "val"
                                  "username" "alice"
                                  "token"    "good"})))
        resp     (app req)]

    ;; Malli-firewall coerces the string keys, safely strips the unknown 'junk-key',
    ;; and successfully processes the login!
    (is (= 303 (:status resp)))
    (is (= "/admin" (get-in resp [:headers "Location"])))

    ;; Most importantly: the malicious key was safely ignored and never interned!
    (is (nil? (find-keyword junk-key))
        "Unknown keys must never be interned as keywords.")))
