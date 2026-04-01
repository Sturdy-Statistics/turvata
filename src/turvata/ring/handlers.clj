(ns turvata.ring.handlers
  (:require
   [ring.util.response :as resp]
   [ring.util.codec :as rcodec]

   [turvata.core :refer [authenticate-browser-token]]
   [turvata.catalog :as cat]
   [turvata.session :as sess]
   [turvata.keys :as keys]
   [turvata.ring.util :refer [clear-cookie-attrs ms->s cookie-attrs]]
   [turvata.schema :as s]

   [sturdy.middleware.cache-control :as cc]
   [sturdy.malli-firewall.web :refer [with-schema]]
   [taoensso.truss :refer [have]]))

(set! *warn-on-reflection* true)

(defn make-logged-in?-handler
  "Return the current session auth map (e.g. {:user-id ...}) if the request is
  already authenticated via the session cookie, else nil. Not a Ring response."
  [env]
  (fn [request]
    (let [cookie-name   (get-in env     [:settings :cookie-name])
          cookie-token  (get-in request [:cookies cookie-name :value])]
      (when cookie-token
        (authenticate-browser-token env cookie-token)))))

(defn make-login-handler
  "POST handler that validates username+token against the TokenCatalog, creates
  a new session in the SessionStore, sets the session cookie, and redirects.

  Redirect target is taken from the :next param (relative paths only) or falls
  back to :post-login-redirect."
  [env]
  (fn [request]
    (with-schema s/TurvataLogin request
      (let [{:keys [catalog store settings]} env
            {:keys [username token next]} (:params request)
            token!!       token

            cookie        (:cookie-name settings)
            ttl-ms        (:session-ttl-ms settings)
            post-login    (:post-login-redirect settings)
            redirect-to   (or next post-login)

            ;; already auth'd?
            cookie-token  (get-in request [:cookies cookie :value])
            already       (when cookie-token
                            (authenticate-browser-token env cookie-token))
            ;; catalog lookup
            user-id       (when token!! (cat/lookup-user-id catalog token!! request))
            ok?           (and user-id username (= username (str user-id)))]

        (cond
          ;; Already logged in → act like a successful login and redirect
          already
          (-> (resp/redirect redirect-to 303)
              cc/with-nostore)

          ;; Valid credentials → create session, set cookie, redirect
          ok?
          (let [session-token  (-> (keys/generate-token) :token!!)
                expires-at     (+ (sess/now-ms) ttl-ms)
                max-age        (ms->s (have pos? ttl-ms))
                cookie-attrs   (assoc (cookie-attrs settings request) :max-age max-age)]
            (sess/put-entry! store session-token {:user-id username :expires-at expires-at})
            (-> (resp/redirect redirect-to 303) ;; See Other after POST
                (resp/set-cookie cookie session-token cookie-attrs)
                cc/with-nostore))

          ;; Bad credentials → bounce back to login with error + next
          :else
          (let [login (have string? (:login-url settings))
                loc   (str login
                           "?error=bad_credentials"
                           (when redirect-to (str "&next=" (rcodec/url-encode redirect-to))))]
            (-> (resp/redirect loc 303)
                cc/with-nostore)))))))

(defn make-logout-handler
  "POST handler Clear cookie + session and redirect to a confirmation page."
  [env]
  (fn [request]
    (with-schema s/EmptyRequest request
      (let [{:keys [store settings]} env
            cookie   (:cookie-name settings)
            redirect (:post-logout-redirect settings)]
        (when-let [token (get-in request [:cookies cookie :value])]
          (sess/delete-entry! store token))
        (let [cookie-settings  (cookie-attrs settings request)]
          (-> (resp/redirect redirect 303) ;; See Other after POST
              (resp/set-cookie cookie "" (clear-cookie-attrs cookie-settings))
              cc/with-nostore))))))
