(ns turvata.ring.middleware-api-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [turvata.test-support :as ts]
   [ring.mock.request :as mock]
   [turvata.catalog :as cat]
   [turvata.ring.middleware :as mw]))

;; Stub catalog: two valid tokens map to two users; everything else is invalid.
(def catalog
  (reify cat/TokenCatalog
    (lookup-user-id [_ token _req]
      (case token
        "good" "alice"
        "xyz"  :bob
        nil))))

(use-fixtures :each
  (fn [f]
    (binding [rt/*runtime* (rt/make-runtime
                            {:settings ts/test-settings
                             :catalog catalog})]
      (f))))

;; A tiny protected handler that echoes the bound :user-id so we can assert it.
(defn echo-user-id [req]
  {:status 200
   :headers {}
   :body {:user-id (:user-id req)}})

(deftest api-auth-success-bearer
  (let [app (mw/require-api-auth echo-user-id)
        req (-> (mock/request :get "/api/data")
                (mock/header "authorization" "Bearer good"))
        resp (app req)]
    (is (= 200 (:status resp)))
    (is (= {:user-id "alice"} (:body resp)))))

(deftest api-auth-success-token-scheme-and-casing
  (let [app (mw/require-api-auth echo-user-id)]
    (doseq [hdr ["Token xyz" "token xyz" "TOKEN xyz"]]
      (let [resp (app (-> (mock/request :get "/api/other")
                          (mock/header "authorization" hdr)))]
        (is (= 200 (:status resp)))
        (is (= {:user-id :bob} (:body resp)))))))

(deftest api-auth-trims-trailing-whitespace-after-scheme
  (let [app  (mw/require-api-auth echo-user-id)
        resp (app (-> (mock/request :get "/api/data")
                      (mock/header "authorization" "Bearer    good     ")))]
    (is (= 200 (:status resp)))
    (is (= {:user-id "alice"} (:body resp)))))

(deftest api-auth-missing-or-malformed-header-401
  (let [handler-called? (atom 0)
        app (mw/require-api-auth
             (fn [req] (swap! handler-called? inc) (echo-user-id req)))]

    ;; No Authorization header at all
    (let [resp (app (mock/request :get "/api/secure"))]
      (is (= 401 (:status resp)))
      (is (= {:error "unauthorized"} (:body resp)))
      (is (re-find #"Bearer realm=\"api\", error=\"invalid_token\""
                   (get-in resp [:headers "WWW-Authenticate"])))
      (is (= "no-store" (get-in resp [:headers "Cache-Control"]))))

    ;; Wrong scheme
    (let [resp (app (-> (mock/request :get "/api/secure")
                        (mock/header "authorization" "Basic abcd")))]
      (is (= 401 (:status resp))))

    ;; Missing token after Bearer
    (let [resp (app (-> (mock/request :get "/api/secure")
                        (mock/header "authorization" "Bearer   ")))]
      (is (= 401 (:status resp))))

    ;; Ensure protected handler never ran on any 401 path
    (is (= 0 @handler-called?))))

(deftest api-auth-invalid-token-401
  (let [app (mw/require-api-auth echo-user-id)
        resp (app (-> (mock/request :get "/api/secure")
                      (mock/header "authorization" "Bearer nope")))]
    (is (= 401 (:status resp)))
    (is (= {:error "unauthorized"} (:body resp)))))
