(ns example-app.routes
  (:require
   [example-app.handlers :as h]
   [example-app.middleware :as m]))

(defn public-routes []
  [""
   {:middleware m/web-middleware}
   ["/" {:get h/home-handler}]])

(defn login-routes [env]
  ["/auth"
   {:middleware m/login-middleware}     ; runs AFTER routing
   ["/login"           {:middleware [m/wrap-require-same-origin-strict]
                        :get  (h/make-login-get-handler  env)
                        :post (h/make-login-post-handler env)}]
   ["/logout"          {:middleware [m/wrap-require-same-origin-strict]
                        :get  h/logout-confirm-handler
                        :post (h/make-logout-post-handler env)}]
   ["/logout/success"  {:get h/logout-success-handler}]])

(defn admin-routes [env]
  ["/admin"
   ;; mw here runs AFTER routing
   {:middleware (m/make-admin-middleware env)}
   [""        {:get  h/admin-home-handler}]])
