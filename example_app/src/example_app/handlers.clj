(ns example-app.handlers
  (:require
   [clojure.string :as string]
   [ring.util.response :as resp]

   [example-app.auth :as a]
   [example-app.views :as views]

   [turvata.ring.handlers :as auth]
   [turvata.schema :as s]

   [sturdy.malli-firewall.web :refer [with-schema]]))

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

(def LoginGetRequest
  [:map {:closed false}
   [:next     {:optional true} s/RelativeURI]
   [:error    {:optional true} string?]

   [:__anti-forgery-token {:optional true} string?]])

(defn login-get-handler
  [request]
  ;; Read `next` from params
  (with-schema LoginGetRequest request

    (let [params   (:params request)
          next (:next params)
          redirect-to (or next (a/post-login-redirect))]

      (if (auth/logged-in?-handler request)
        (resp/redirect redirect-to 303)
        (resp/response (views/login-page
                        (assoc request :next next)))))))

(defn admin-home-handler [request]
  ;; request has :user-id due to auth/require-web-auth
  (resp/response (views/admin-home request)))

(defn home-handler [request]
  (resp/response (views/home request)))
