(ns turvata.ring.middleware-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as string]
   [ring.mock.request :as mock]
   [turvata.catalog :as cat]
   [turvata.codec :as codec]
   [turvata.crypto :as crypto]
   [turvata.test-support :as ts]
   [turvata.settings :as settings]
   [turvata.ring.middleware :as mw]))

(set! *warn-on-reflection* true)

;;; Generate valid data for multiple users
(def alice-uuid (random-uuid))
(def bob-uuid   (random-uuid))

(def alice-tok-str (codec/generate-token!! {:prefix "sturdy-test" :rotation-version 1 :user-id alice-uuid}))
(def bob-tok-str   (codec/generate-token!! {:prefix "sturdy-test" :rotation-version 1 :user-id bob-uuid}))

(def alice-hash (crypto/hash-key ts/test-pepper-bytes (codec/parse-token!! alice-tok-str)))
(def bob-hash   (crypto/hash-key ts/test-pepper-bytes (codec/parse-token!! bob-tok-str)))

;;; Mock Catalog strictly returning database rows for known UUIDs
(def catalog
  (reify cat/TokenCatalog
    (lookup-record [_ user-id-uuid _req]
      (cond
        (= user-id-uuid alice-uuid) {:hash alice-hash :rotation-version 1}
        (= user-id-uuid bob-uuid)   {:hash bob-hash :rotation-version 1}
        :else nil))))

(def api-env {:catalog catalog :settings (settings/normalize ts/test-settings)})

;; A protected handler that echoes the bound :user-id so we can assert it.
(defn echo-user-id [req]
  {:status 200
   :headers {}
   :body {:user-id (:user-id req)}})

(deftest api-auth-success-bearer
  (let [app ((mw/require-api-auth api-env) echo-user-id)
        req (-> (mock/request :get "/api/data")
                (mock/header "authorization" (str "Bearer " alice-tok-str)))
        resp (app req)]
    (is (= 200 (:status resp)))
    (is (= {:user-id alice-uuid} (:body resp)))))

(deftest api-auth-success-token-scheme-and-casing
  (let [app ((mw/require-api-auth api-env) echo-user-id)]
    (doseq [hdr [(str "Token " bob-tok-str) (str "token " bob-tok-str) (str "TOKEN " bob-tok-str)]]
      (let [resp (app (-> (mock/request :get "/api/other")
                          (mock/header "authorization" hdr)))]
        (is (= 200 (:status resp)))
        (is (= {:user-id bob-uuid} (:body resp)))))))

(deftest api-auth-trims-trailing-whitespace-after-scheme
  (let [app  ((mw/require-api-auth api-env) echo-user-id)
        resp (app (-> (mock/request :get "/api/data")
                      (mock/header "authorization" (str "Bearer    " alice-tok-str "     "))))]
    (is (= 200 (:status resp)))
    (is (= {:user-id alice-uuid} (:body resp)))))

(deftest api-auth-missing-or-malformed-header-401
  (let [handler-called? (atom 0)
        app ((mw/require-api-auth api-env)
             (fn [req] (swap! handler-called? inc) (echo-user-id req)))]

    (testing "No Authorization header at all"
      (let [resp (app (mock/request :get "/api/secure"))]
        (is (= 401 (:status resp)))
        (is (= "Unauthorized" (get-in resp [:body :error])))
        (is (re-find #"Bearer realm=\"turvata\", error=\"invalid_token\""
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
  (let [app ((mw/require-api-auth api-env) echo-user-id)]
    (testing "Rejects malformed strings"
      (let [resp (app (-> (mock/request :get "/api/secure")
                          (mock/header "authorization" "Bearer nope")))]
        (is (= 401 (:status resp)))
        (is (= "Unauthorized" (get-in resp [:body :error])))))

    (testing "Rejects structurally valid tokens missing from the DB"
      (let [unknown-tok (codec/generate-token!! {:prefix "sturdy-test" :rotation-version 1 :user-id (random-uuid)})
            resp (app (-> (mock/request :get "/api/secure")
                          (mock/header "authorization" (str "Bearer " unknown-tok))))]
        (is (= 401 (:status resp)))
        (is (= "Unauthorized" (get-in resp [:body :error])))))))
