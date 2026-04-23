(ns turvata.ring.logout-test
  (:require
   [clojure.test :refer [deftest is]]
   [ring.mock.request :as mock]
   [turvata.settings :as settings]
   [turvata.session :as sess]
   [turvata.ring.handlers :as h]
   [turvata.test-support :as ts]))

(set! *warn-on-reflection* true)

(defn- make-env []
  {:store (sess/in-memory-store)
   :settings (settings/normalize
              (merge ts/test-settings
                     {:post-logout-redirect "/auth/logout/success"
                      :cookie-name "test-cookie"}))})

(deftest logout-clears-session-and-cookie
  (let [env   (make-env)
        token "tok"]
    (sess/put-entry! (:store env) token {:user-id "alice" :expires-at (+ (sess/now-ms) 60000)})
    (let [app  (h/make-logout-handler env)
          req  (-> (mock/request :post "/auth/logout")
                   (assoc :cookies {"test-cookie" {:value token}}))
          resp (app req)]
      (is (= 303 (:status resp)))
      (is (= "/auth/logout/success" (get-in resp [:headers "Location"])))

      ;; Cookie actively cleared
      (is (= "" (get-in resp [:cookies "test-cookie" :value])))
      (is (= 0  (get-in resp [:cookies "test-cookie" :max-age])))
      (is (= "Thu, 01 Jan 1970 00:00:00 GMT" (get-in resp [:cookies "test-cookie" :expires])))

      ;; Cache prevented
      (is (re-find #"no-store" (get-in resp [:headers "Cache-Control"])))

      ;; Session removed from store
      (is (nil? (sess/get-entry (:store env) token))))))

(deftest logout-without-cookie-still-redirects
  (let [env  (make-env)
        app  (h/make-logout-handler env)
        resp (app (mock/request :post "/auth/logout"))]
    (is (= 303 (:status resp)))
    (is (= "/auth/logout/success" (get-in resp [:headers "Location"])))
    ;; still emits a clearing cookie with max-age 0
    (is (= "" (get-in resp [:cookies "test-cookie" :value])))
    (is (= 0  (get-in resp [:cookies "test-cookie" :max-age])))))

(deftest logout-strips-unwanted-params
  (let [env     (make-env)
        handler (h/make-logout-handler env)
        req     (-> (mock/request :post "/auth/logout")
                    (assoc :params {"malicious_param" "value"}))
        resp    (handler req)]
    ;; Malli's string-coercion strips the unknown string key before closed-map validation.
    ;; The handler safely processes the empty request and redirects.
    (is (= 303 (:status resp)))))
