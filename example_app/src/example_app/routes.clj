(ns example-app.routes
  (:require
   [example-app.handlers :as h]
   [example-app.middleware :as m]))

(defn public-routes []
  [""
   {:middleware m/web-middleware}
   ["/" {:get h/home-handler}]])

(defn login-routes []
  ["/auth"
   {:middleware m/login-middleware}     ; runs AFTER routing
   ["/login"           {:middleware [m/wrap-require-same-origin-strict]
                        :get  h/login-get-handler
                        :post h/login-post-handler}]
   ["/logout"          {:middleware [m/wrap-require-same-origin-strict]
                        :get  h/logout-confirm-handler
                        :post h/logout-post-handler}]
   ["/logout/success"  {:get h/logout-success-handler}]])

(defn admin-routes []
  ["/admin"
   ;; mw here runs AFTER routing
   {:middleware m/admin-middleware}
   [""        {:get  h/admin-home-handler}]])
