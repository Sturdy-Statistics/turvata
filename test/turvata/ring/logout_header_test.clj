(ns turvata.ring.logout-header-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [turvata.test-support :as ts]
   [ring.mock.request :as mock]
   [ring.middleware.cookies :refer [wrap-cookies]]
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

(deftest logout-sets-set-cookie-header
  (let [cookie   (rt/settings [:cookie-name])
        app      (wrap-cookies h/logout-handler)
        resp     (app (mock/request :post "/auth/logout"))
        set-cookie (get-in resp [:headers "Set-Cookie"])
        ;; normalize to a seq of strings
        cookies   (cond
                    (nil? set-cookie) []
                    (sequential? set-cookie) set-cookie
                    :else [set-cookie])
        pat (re-pattern (str "^" (java.util.regex.Pattern/quote cookie) "="))]
    (is (= 303 (:status resp)))
    ;; at least one Set-Cookie header starts with "<cookie-name>="
    (is (some #(re-find pat %) cookies))
    ;; optionally assert it's being cleared
    (is (some #(re-find #";\s*Max-Age=0\b" %) cookies))))
