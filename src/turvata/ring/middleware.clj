(ns turvata.ring.middleware
  (:require
   [clojure.string :as string]

   [ring.util.response :as resp]
   [ring.util.codec :as rcodec]

   [turvata.core :refer [authenticate-browser-token]]
   [turvata.session :as s]
   [turvata.catalog :as c]
   [turvata.runtime :as rt]

   [taoensso.truss :refer [have]]))

(def ^:private bearer-re #"(?i)^(?:Bearer|Token)\s+(.+)\s*$")

(defn ms->s [ms] (quot (long ms) 1000))

(defn require-api-auth
  "Ring middleware that requires an API token in the Authorization header.

  Expects: Authorization: Bearer <token> (also accepts Token <token>).
  On success, assoc :user-id in the request.
  On failure, returns 401 with WWW-Authenticate and Cache-Control: no-store."
  [handler]
  (fn [request]
    (rt/require-runtime)
    (let [auth-header (get-in request [:headers "authorization"])
          token       (some->> auth-header
                               (re-find bearer-re)
                               second
                               string/trim
                               not-empty)
          user-id     (when token (c/lookup-user-id (rt/catalog) token))]
      (if-not user-id
        (-> (resp/response {:error "unauthorized"})
            (resp/status 401)
            (resp/header "WWW-Authenticate" "Bearer realm=\"api\", error=\"invalid_token\"")
            (resp/header "Cache-Control" "no-store")
            (resp/header "Vary" "Authorization"))
        (handler (assoc request :user-id user-id))))))

(defn require-web-auth
  "Ring middleware that requires a valid session cookie.

  Looks up the session token from the configured cookie name.
  On success, assoc :user-id in the request. May refresh the cookie on response.
  On failure, clears the cookie and redirects to the configured login URL with
  a ?next=... parameter for the current request URI."
  [handler]
  (fn [request]
    (rt/require-runtime)
    (let [cookie-name      (have string? (rt/settings [:cookie-name]))
          token            (get-in request [:cookies cookie-name :value])
          cookie-settings  (rt/cookie-attrs request)
          auth             (authenticate-browser-token token)]

      (if-let [{:keys [user-id expires-at refreshed?]} auth]
        ;; authenticated path... nil-preserving!
        (when-let [response (handler (assoc request :user-id user-id))]
          (if refreshed?
            ;; refresh cookie if session refreshed
            (let [remaining        (- expires-at (s/now-ms))
                  max-age          (ms->s (max 0 remaining))
                  cookie-settings' (assoc cookie-settings :max-age max-age)]
              (-> response
                  (resp/set-cookie cookie-name token cookie-settings')
                  (resp/header "Vary" "Cookie")))
            ;; don't change cookie
            response))

        ;; unauthenticated → redirect to login with ?next=<current path+qs>
        (let [target     (str (:uri request)
                              (when-let [qs (:query-string request)] (str "?" qs)))
              login-url  (have string? (rt/settings [:login-url]))
              loc        (str login-url "?next=" (rcodec/url-encode target))
              cookie-settings' (assoc cookie-settings :max-age 0)]
          (-> (resp/redirect loc)
              (resp/set-cookie cookie-name "" cookie-settings')
              (resp/header "Cache-Control" "no-store")
              (resp/header "Vary" "Cookie")))))))
