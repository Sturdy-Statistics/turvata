(ns turvata.ring.logout-header-test
  (:require
   [clojure.test :refer [deftest is]]
   [ring.mock.request :as mock]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [turvata.settings :as settings]
   [turvata.session :as sess]
   [turvata.ring.handlers :as h]
   [turvata.test-support :as ts])
  (:import
   (java.util.regex Pattern)))

(defn- make-env []
  {:store (sess/in-memory-store)
   :settings (settings/normalize
              (merge ts/test-settings
                     {:post-logout-redirect "/auth/logout/success"
                      :cookie-name "test-cookie"}))})

(deftest logout-sets-set-cookie-header
  (let [env        (make-env)
        app        (wrap-cookies (h/make-logout-handler env))
        resp       (app (mock/request :post "/auth/logout"))
        set-cookie (get-in resp [:headers "Set-Cookie"])
        ;; normalize to a seq of strings
        cookies    (cond
                     (nil? set-cookie) []
                     (sequential? set-cookie) set-cookie
                     :else [set-cookie])
        pat        (re-pattern (str "^" (Pattern/quote "test-cookie") "="))]

    (is (= 303 (:status resp)))
    ;; at least one Set-Cookie header starts with "<cookie-name>="
    (is (some #(re-find pat %) cookies))

    ;; Assert it is being completely cleared
    (is (some #(re-find #";\s*Max-Age=0\b" %) cookies))
    (is (some #(re-find #"Expires=Thu, 01 Jan 1970 00:00:00 GMT" %) cookies))))
