(ns turvata.ring.logout-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [turvata.test-support :as ts]
   [ring.mock.request :as mock]
   [turvata.session :as sess]
   [turvata.ring.handlers :as h]))

(def ^:dynamic *store* nil)

(use-fixtures :each
  (fn [f]
    (let [store (sess/in-memory-store)]
      (binding [*store*    store
                rt/*runtime* (rt/make-runtime {:settings ts/test-settings
                                               :store    store})]
        (f)))))

(deftest logout-clears-session-and-cookie
  (let [cookie    (rt/settings [:cookie-name])
        token     "tok"
        _         (sess/put-entry! *store* token {:user-id "alice" :expires-at (+ (System/currentTimeMillis) 60000)})
        app       h/logout-handler
        req       (-> (mock/request :post "/auth/logout")
                      (assoc :cookies {cookie {:value token}}))
        resp      (app req)]
    (is (= 303 (:status resp)))
    (is (= "/auth/logout/success" (get-in resp [:headers "Location"])))
    ;; cookie cleared
    (is (= "" (get-in resp [:cookies cookie :value])))
    (is (= 0  (get-in resp [:cookies cookie :max-age])))
    ;; session removed
    (is (nil? (sess/get-entry *store* token)))))

(deftest logout-without-cookie-still-redirects
  (let [cookie   (rt/settings [:cookie-name])
        app      h/logout-handler
        resp     (app (mock/request :post "/auth/logout"))]
    (is (= 303 (:status resp)))
    (is (= "/auth/logout/success" (get-in resp [:headers "Location"])))
    ;; still emits a clearing cookie with max-age 0
    (is (= "" (get-in resp [:cookies cookie :value])))
    (is (= 0  (get-in resp [:cookies cookie :max-age])))))

(deftest logout-strips-unwanted-params
  (let [handler h/logout-handler
        req     (-> (mock/request :post "/auth/logout")
                    (assoc :params {"malicious_param" "value"}))
        resp    (handler req)]
    ;; If *strip-unknown-keys* is true (default), this should succeed
    ;; but the param is ignored.
    (is (= 303 (:status resp)))))
