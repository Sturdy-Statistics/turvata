(ns example-app.core
  (:require
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [example-app.routes :as routes]
   [example-app.auth :as auth]))

;;; middleware stacks



(defn app []
  ;; Initialize turvata once on startup
  (auth/init-turvata!)
  (ring/ring-handler
   (ring/router
    [(routes/public-routes)
     (routes/login-routes)
     (routes/admin-routes)]
    ;; global middleware BEFORE routing:
    {:data {:middleware [wrap-params wrap-keyword-params]}})
   (ring/create-default-handler)))

(defn -main [& _args]
  (let [handler (app)]
    (jetty/run-jetty handler {:port 3000 :join? false})
    (println "server running on port 3000")))
