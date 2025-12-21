(ns turvata.ring.middleware-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [ring.mock.request :as mock]
   [turvata.session :as sess]
   [turvata.ring.middleware :as mw]))

(def store (sess/in-memory-store))

(use-fixtures :each
  (fn [f]
    (binding [rt/*runtime* (rt/make-runtime
                            {:settings {:session-ttl-ms 60000}
                             :store store})]
      (f))))

(defn ok-handler [_] {:status 200 :headers {} :body "OK"})

(deftest web-auth-redirects-when-missing
  (let [app (mw/require-web-auth ok-handler)
        resp (app (mock/request :get "/admin?x=1"))]
    (is (= 302 (:status resp)))
    (is (re-find #"/auth/login\?next=%2Fadmin%3Fx%3D1" (get-in resp [:headers "Location"])))))

(deftest web-auth-refreshes-cookie-when-needed
  (let [token      "tok"
        base       1000000
        cookie     (rt/settings [:cookie-name])
        ;; seed a session that is about to cross half TTL so authenticate-browser-token will refresh
        _       (with-redefs [sess/now-ms (fn [] base)]
                  (sess/put-entry! store token {:user-id "alice" :expires-at (+ base 30100)}))
        app     (mw/require-web-auth ok-handler)
        req     (-> (mock/request :get "/admin")
                    (mock/header :host "example.test")
                    (assoc :cookies {cookie {:value token}}))
        resp    (with-redefs [sess/now-ms (fn [] (+ base 30010))]
                  (app req))]
    (is (= 200 (:status resp)))
    (is (some? (get-in resp [:cookies cookie])))
    (is (contains? (get-in resp [:cookies cookie]) :max-age))))
