(ns turvata.ring.middleware
  (:require
   [clojure.string :as string]

   [ring.util.response :as resp]
   [ring.util.codec :as rcodec]

   [turvata.core :as core]
   [turvata.session :as s]

   [turvata.ring.util :refer [clear-cookie-attrs ms->s cookie-attrs]]

   [sturdy.middleware.cache-control :as cc]
   [taoensso.truss :refer [have]]))

(set! *warn-on-reflection* true)

(def ^:private bearer-re #"(?i)^(?:Bearer|Token)\s+(.+)\s*$")

(defn- unauthorized-response [message]
  (-> (resp/response {:error "Unauthorized" :message message})
      (resp/status 401)
      (resp/header "WWW-Authenticate" "Bearer realm=\"turvata\", error=\"invalid_token\"")
      cc/with-nostore
      (resp/header "Vary" "Authorization")))

(defn require-api-auth
  "Ring middleware that requires a valid V2 API token in the Authorization header.
   Parses the token, fetches the DB record via UUID, and validates the HMAC signature."
  [env]
  (fn [handler]
    (fn [request]
      (let [auth-header (get-in request [:headers "authorization"])
            raw-token!! (some->> auth-header
                                 (re-find bearer-re)
                                 second
                                 string/trim
                                 not-empty)]
        (if-not raw-token!!
          (unauthorized-response "Request lacks authentication credentials")

          (let [auth-result (core/authenticate-api-token raw-token!! env request)]
            (if auth-result
              (handler (assoc request :user-id auth-result))
              (unauthorized-response "Invalid token signature, rotation version, or expired key"))))))))

(defn require-web-auth
  "Ring middleware that requires a valid stateful session cookie.
   Unchanged structurally, as it relies on turvata.session logic."
  [env]
  (fn [handler]
    (fn [request]
      (let [{:keys [settings]} env
            cookie-name      (have string? (:cookie-name settings))
            token!!          (get-in request [:cookies cookie-name :value])
            cookie-settings  (cookie-attrs settings request)
            auth             (s/authenticate-browser-token env token!!)]

        (if-let [{:keys [user-id expires-at refreshed?]} auth]
          ;; authenticated path... nil-preserving!
          (when-let [response (handler (assoc request :user-id user-id))]
            (if refreshed?
              ;; refresh cookie if session refreshed
              (let [remaining        (- expires-at (s/now-ms))
                    max-age          (ms->s (max 0 remaining))
                    cookie-settings' (assoc cookie-settings :max-age max-age)]
                (-> response
                    (resp/set-cookie cookie-name token!! cookie-settings')
                    cc/with-vary-cookie))
              ;; don't change cookie
              response))

          ;; unauthenticated → redirect to login with ?next=<current path+qs>
          (let [target     (str (:uri request)
                                (when-let [qs (:query-string request)] (str "?" qs)))
                login-url  (have string? (:login-url settings))
                loc        (str login-url "?next=" (rcodec/url-encode target))]
            (-> (resp/redirect loc)
                (resp/set-cookie cookie-name "" (clear-cookie-attrs cookie-settings))
                cc/with-nostore
                cc/with-vary-cookie)))))))
