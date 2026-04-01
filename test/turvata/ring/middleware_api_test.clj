(ns turvata.ring.middleware-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as string]
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

(def api-env {:catalog catalog})

;; A tiny protected handler that echoes the bound :user-id so we can assert it.
(defn echo-user-id [req]
  {:status 200
   :headers {}
   :body {:user-id (:user-id req)}})

(deftest api-auth-success-bearer
  (let [app ((mw/require-api-auth api-env) echo-user-id)
        req (-> (mock/request :get "/api/data")
                (mock/header "authorization" "Bearer good"))
        resp (app req)]
    (is (= 200 (:status resp)))
    (is (= {:user-id "alice"} (:body resp)))))

(deftest api-auth-success-token-scheme-and-casing
  (let [app ((mw/require-api-auth api-env) echo-user-id)]
    (doseq [hdr ["Token xyz" "token xyz" "TOKEN xyz"]]
      (let [resp (app (-> (mock/request :get "/api/other")
                          (mock/header "authorization" hdr)))]
        (is (= 200 (:status resp)))
        (is (= {:user-id :bob} (:body resp)))))))

(deftest api-auth-trims-trailing-whitespace-after-scheme
  (let [app  ((mw/require-api-auth api-env) echo-user-id)
        resp (app (-> (mock/request :get "/api/data")
                      (mock/header "authorization" "Bearer    good     ")))]
    (is (= 200 (:status resp)))
    (is (= {:user-id "alice"} (:body resp)))))

(deftest api-auth-missing-or-malformed-header-401
  (let [handler-called? (atom 0)
        app ((mw/require-api-auth api-env)
             (fn [req] (swap! handler-called? inc) (echo-user-id req)))]

    (testing "No Authorization header at all"
      (let [resp (app (mock/request :get "/api/secure"))]
        (is (= 401 (:status resp)))
        (is (= {:error "unauthorized"} (:body resp)))
        (is (re-find #"Bearer realm=\"api\", error=\"invalid_token\""
                     (get-in resp [:headers "WWW-Authenticate"])))
        (is (string/includes? (get-in resp [:headers "Cache-Control"]) "no-store"))
        (is (re-find #"Authorization" (get-in resp [:headers "Vary"])))))

    (testing "Wrong scheme"
      (let [resp (app (-> (mock/request :get "/api/secure")
                          (mock/header "authorization" "Basic abcd")))]
        (is (= 401 (:status resp)))))

    (testing "Missing token after Bearer"
      (let [resp (app (-> (mock/request :get "/api/secure")
                          (mock/header "authorization" "Bearer   ")))]
        (is (= 401 (:status resp)))))

    (testing "Ensure protected handler never ran on any 401 path"
      (is (= 0 @handler-called?)))))

(deftest api-auth-invalid-token-401
  (let [app ((mw/require-api-auth api-env) echo-user-id)
        resp (app (-> (mock/request :get "/api/secure")
                      (mock/header "authorization" "Bearer nope")))]
    (is (= 401 (:status resp)))
    (is (= {:error "unauthorized"} (:body resp)))))
