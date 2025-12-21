(ns turvata.ring.login-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [ring.mock.request :as mock]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.util.codec :as rcodec]          ;; for form encoding

   [turvata.catalog :as cat]
   [turvata.session :as sess]
   [turvata.ring.handlers :as h]))

(def ^:dynamic *store* nil)

(def catalog
  (reify cat/TokenCatalog
    (lookup-user-id [_ token] ({"good" "alice"} token))))

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
                     wrap-keyword-params
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
        resp    (handler (mock/request :post "/auth/login" {"username" "alice" "token" "nope"}))]
    (is (= 303 (:status resp)))
    (is (re-find #"/auth/login\?error=bad_credentials" (get-in resp [:headers "Location"])))))

(deftest login-preserves-next-on-success
  (let [app        (-> h/login-handler
                       wrap-keyword-params
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
    (let [req  (-> (mock/request :post "/auth/login" {"username" "alice" "token" "good"})
                   (assoc :cookies {cookie {:value token}}))
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= {:message "Already logged in"} (:body resp))))))
