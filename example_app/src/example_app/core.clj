(ns example-app.core
  (:require
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.response :as resp]
   [sturdy.malli-firewall.web :as f]
   [example-app.routes :as routes]
   [example-app.auth :as auth]))

(defn bad-request-response
  [_request details]
  (let [flat-message (f/format-schema-error details)]
    (-> (resp/response (format "<h1>Bad Request</h1> <p>%s</p>" flat-message))
        (resp/content-type "text/html; charset=utf-8"))))

(alter-var-root #'f/*bad-request-handler*
                (constantly bad-request-response))

(defn app []
  ;; Initialize turvata once on startup
  (auth/init-turvata!)
  (ring/ring-handler
   (ring/router
    [(routes/public-routes)
     (routes/login-routes)
     (routes/admin-routes)]
    ;; global middleware BEFORE routing:
    {:data {:middleware [wrap-params]}})
   (ring/create-default-handler)))

(defn -main [& _args]
  (let [handler (app)]
    (jetty/run-jetty handler {:port 3000 :join? false})
    (println "server running on port 3000")))
