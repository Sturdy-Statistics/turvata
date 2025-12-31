(ns example-app.middleware
  (:require
   [ring.util.response :as resp]

   ;; session + anti-forgery
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.memory :refer [memory-store]]
   [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]

   ;; auth
   [turvata.ring.middleware :as auth]))

;;; define a shared session store for anti-forgery token
(defonce session-store (memory-store))
;;; cookie-store alternative: needs a secret key

(def web-middleware
  "Middleware for public web routes.  These run AFTER routing.
   Note that these are applied to a request IN ORDER listed"
  [])

(def login-middleware
  "Middleware for login routes. (which are public).
   These run AFTER routing, and IN ORDER listed"
  (concat
   web-middleware                       ;all the web stuff
   [[wrap-session                       ;add a session
     {:store session-store
      :cookie-attrs {:http-only true :same-site :strict}}]
    wrap-anti-forgery]))                ;anti-forgery uses the session

(def admin-middleware
  "Middleware for admin routes. (which are private).
   These run AFTER routing, and IN ORDER listed
   This matches login-middleware, with auth/require-web-auth added"
  (concat
   login-middleware                    ;web stuff+session+anti-forgery
   [auth/require-web-auth]))           ;session auth

;;; same origin

(defn- same-origin-strict?
  [req]
  (let [origin (get-in req [:headers "origin"])
        host   (get-in req [:headers "host"])
        scheme (if (= "https" (get-in req [:headers "x-forwarded-proto"])) "https" "http")]
    (= origin (str scheme "://" host))))

(defn wrap-require-same-origin-strict
  "Additional protection on top of `wrap-anti-forgery`.
  Use on POST requests which should be same-origin."
  [handler]
  (fn [req]
    (if (or (not= :post (:request-method req)) (same-origin-strict? req))
      (handler req)
      (-> (resp/response "Forbidden (Not Same-Origin)")
          (resp/status 403)))))
