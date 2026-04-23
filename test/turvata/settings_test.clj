(ns turvata.settings-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [turvata.settings :as s]
   [turvata.test-support :as ts]))

(deftest default-https-test
  (testing "basic scheme"
    (is (true?  (s/default-https? {:scheme :https :headers {}})))
    (is (false? (s/default-https? {:scheme :http :headers {}}))))

  (testing "x-forwarded-proto"
    (is (true?  (s/default-https? {:scheme :http :headers {"x-forwarded-proto" "https"}})))
    (is (true?  (s/default-https? {:scheme :http :headers {"x-forwarded-proto" "HTTPS"}}))) ; case-insensitive
    (is (false? (s/default-https? {:scheme :http :headers {"x-forwarded-proto" "http"}}))))

  (testing "x-forwarded-port"
    (is (true?  (s/default-https? {:scheme :http :headers {"x-forwarded-port" "443"}})))
    (is (false? (s/default-https? {:scheme :http :headers {"x-forwarded-port" "80"}}))))

  (testing "x-forwarded-ssl"
    (is (true?  (s/default-https? {:scheme :http :headers {"x-forwarded-ssl" "on"}})))
    (is (true?  (s/default-https? {:scheme :http :headers {"x-forwarded-ssl" "true"}})))
    (is (false? (s/default-https? {:scheme :http :headers {"x-forwarded-ssl" "off"}}))))

  (testing "front-end-https"
    (is (true?  (s/default-https? {:scheme :http :headers {"front-end-https" "on"}})))
    (is (true?  (s/default-https? {:scheme :http :headers {"front-end-https" "ON"}})))
    (is (false? (s/default-https? {:scheme :http :headers {"front-end-https" "off"}})))))

(deftest normalize-test
  (testing "merges with sane defaults"
    (let [env (s/normalize ts/test-settings)]
      (is (= "turvata-web-token" (:cookie-name env)))
      (is (= (* 4 60 60 1000) (:session-ttl-ms env)))
      (is (= :lax (:same-site env)))
      (is (= "/auth/login" (:login-url env)))
      (is (= s/default-https? (:https? env)))
      (is (= "sturdy-test" (:prefix env)))
      (is (bytes? (:pepper env)))))

  (testing "allows valid overrides"
    (let [env (s/normalize (merge ts/test-settings
                                  {:cookie-name    "sturdy-admin"
                                   :session-ttl-ms 60000
                                   :same-site      :strict
                                   :login-url      "/custom-login"}))]
      (is (= "sturdy-admin"  (:cookie-name env)))
      (is (= 60000           (:session-ttl-ms env)))
      (is (= :strict         (:same-site env)))
      (is (= "/custom-login" (:login-url env)))))

  (testing "malli strictly rejects invalid pepper and prefix"
    ;; Missing required crypto configs
    (is (thrown? Exception (s/normalize {})))
    (is (thrown? Exception (s/normalize {:prefix "test"})))

    ;; Pepper entropy failures (too short, or a string instead of byte[])
    (is (thrown? Exception (s/normalize {:pepper (byte-array 16) :prefix "test"})))
    (is (thrown? Exception (s/normalize {:pepper "this-is-a-string-not-a-byte-array" :prefix "test"})))

    ;; Prefix constraint failures (no underscores allowed!)
    (is (thrown? Exception (s/normalize {:pepper ts/test-pepper-bytes :prefix "sturdy_admin"})))
    (is (thrown? Exception (s/normalize {:pepper ts/test-pepper-bytes :prefix "sturdy admin"}))))

  (testing "malli strictly rejects invalid configurations"
    ;; Blank/nil cookie names
    (is (thrown? Exception (s/normalize {:cookie-name ""})))
    (is (thrown? Exception (s/normalize {:cookie-name nil})))

    ;; Negative TTL
    (is (thrown? Exception (s/normalize {:session-ttl-ms -1})))

    ;; Open redirect vectors in URLs (must be relative!)
    (is (thrown? Exception (s/normalize {:login-url "https://evil.com/login"})))
    (is (thrown? Exception (s/normalize {:post-login-redirect "//evil.com"})))

    ;; Invalid enums
    (is (thrown? Exception (s/normalize {:same-site :invalid-enum})))

    ;; Closed map prevents unknown keys (typo prevention)
    (is (thrown? Exception (s/normalize {:session-ttl 1000})))))
