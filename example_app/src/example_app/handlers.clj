(ns example-app.handlers
  (:require
   [clojure.string :as string]
   [ring.util.response :as resp]

   [example-app.auth :as a]
   [example-app.views :as views]

   [turvata.ring.handlers :as auth]))

(defn- safe-next
  "Return a safe internal redirect target or nil.
   Allows only absolute paths on this host (e.g., \"/admin\"),
   disallows protocol-relative (\"//...\") and external URLs."
  [s]
  (when (and (string? s)
             (string/starts-with? s "/")
             (not (string/starts-with? s "//")))
    s))

;;; LoginRequest
(def login-post-handler auth/login-handler)

;;; EmptyRequest
(def logout-post-handler auth/logout-handler)

;;; EmptyRequest
(defn logout-success-handler
  [request]
  (resp/response (views/logout-success request)))

(defn logout-confirm-handler
  [request]
  (resp/response (views/confirm-logout request)))

;;; LoginRequest
(defn login-get-handler
  [request]
  ;; Read `next` from params
  (let [params   (:params request)
        next-raw (:next params)
        next     (some-> next-raw safe-next)
        redirect-to (or next (a/post-login-redirect))]

    (if (auth/logged-in?-handler request)
      (resp/redirect redirect-to 303)
      (resp/response (views/login-page
                      (assoc request :next next))))))

(defn admin-home-handler [request]
  ;; request has :user-id due to auth/require-web-auth
  (resp/response (views/admin-home request)))

(defn home-handler [request]
  (resp/response (views/home request)))
