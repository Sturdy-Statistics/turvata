(ns turvata.ring.login-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [ring.mock.request :as mock]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.codec :as rcodec]          ;; for form encoding

   [turvata.catalog :as cat]
   [turvata.session :as sess]
   [turvata.ring.handlers :as h]))

(def ^:dynamic *store* nil)

(def catalog
  (reify cat/TokenCatalog
    (lookup-user-id [_ token _req] ({"good" "alice"} token))))

(use-fixtures :each
  (fn [f]
    (let [store (sess/in-memory-store)]
      (binding [*store*    store
                rt/*runtime* (rt/make-runtime {:settings {:session-ttl-ms 60000
                                                          :post-login-redirect "/admin"}
                                               :store    store
                                               :catalog  catalog})]
        (f)))))


(deftest login-success-sets-cookie-and-redirects
  (let [app      (-> h/login-handler
                     wrap-params)
        req      (-> (mock/request :post "/auth/login")
                     (mock/header "content-type" "application/x-www-form-urlencoded")
                     (mock/body (rcodec/form-encode
                                 {"username" "alice"
                                  "token" "good"})))
        resp     (app req)
        cookie   (rt/settings [:cookie-name])]
    (is (= 303 (:status resp)))
    (is (= "/admin" (get-in resp [:headers "Location"])))
    (is (= "alice" (:user-id (sess/get-entry *store* (get-in resp [:cookies cookie :value])))))
    (is (pos? (get-in resp [:cookies cookie :max-age])))))

(deftest login-bad-credentials-redirects-to-login
  (let [handler h/login-handler
        request (-> (mock/request :post "/auth/login")
                    (assoc :params {"username" "alice" "token" "nope"}))
        resp    (handler request)]

    (is (= 303 (:status resp)))
    (is (re-find #"/auth/login\?error=bad_credentials" (get-in resp [:headers "Location"])))))

(deftest login-preserves-next-on-success
  (let [app        (-> h/login-handler
                       wrap-params)
        req        (-> (mock/request :post "/auth/login")
                       (mock/header "content-type" "application/x-www-form-urlencoded")
                       (mock/body (rcodec/form-encode
                                   {"username" "alice"
                                    "token"    "good"
                                    "next"     "/reports?page=2"})))
        resp       (app req)]
    (is (= 303 (:status resp)))
    (is (= "/reports?page=2" (get-in resp [:headers "Location"])))))

(deftest already-logged-in-returns-200
  (let [handler  h/login-handler
        cookie   (rt/settings [:cookie-name])
        token    "sess1"
        now      (System/currentTimeMillis)]
    ;; seed a valid session cookie
    (sess/put-entry! *store* token {:user-id "alice" :expires-at (+ now 60000)})
    (let [req  (-> (mock/request :post "/auth/login")
                   (assoc :params {"username" "alice" "token" "good"})
                   (assoc :cookies {cookie {:value token}}))
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= {:message "Already logged in"} (:body resp))))))

(deftest login-blocks-malicious-redirects
  (let [handler h/login-handler
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
  (let [handler h/login-handler
        junk-key (str "attack-" (java.util.UUID/randomUUID))
        req     (-> (mock/request :post "/auth/login")
                    (assoc :params {junk-key "val"
                                    "username" "alice"
                                    "token" "good"}))
        _       (handler req)]
    (is (nil? (find-keyword junk-key))
        "Unknown keys must never be interned as keywords.")))
