(ns turvata.ring.handlers
  (:require
   [ring.util.response :as resp]
   [ring.util.codec :as rcodec]

   [turvata.runtime :as rt]
   [turvata.core :refer [authenticate-browser-token]]
   [turvata.catalog :as cat]
   [turvata.session :as sess]
   [turvata.keys :as keys]
   [turvata.ring.util :refer [clear-cookie-attrs ms->s]]
   [turvata.schema :as s]

   [sturdy.middleware.cache-control :as cc]
   [sturdy.malli-firewall.web :refer [with-schema]]
   [taoensso.truss :refer [have]]))

(set! *warn-on-reflection* true)

(defn logged-in?-handler
  "Return the current session auth map (e.g. {:user-id ...}) if the request is
  already authenticated via the session cookie, else nil. Not a Ring response."
  [request]
  (rt/require-runtime)
  (let [cookie-name (rt/settings [:cookie-name])
        cookie-token  (get-in request [:cookies cookie-name :value]) ;may be nil
        ;; already-auth’d?
        already       (when cookie-token
                        (authenticate-browser-token cookie-token))]
    already))

(defn login-handler
  "POST handler that validates username+token against the TokenCatalog, creates
  a new session in the SessionStore, sets the session cookie, and redirects.

  Redirect target is taken from the :next param (relative paths only) or falls
  back to :post-login-redirect."
  [request]
  (rt/require-runtime)
  (with-schema s/TurvataLogin request
    (let [{:keys [username token next]} (:params request) ;; Guaranteed Keywords!
          token!!       token

          cookie        (rt/settings [:cookie-name])
          ttl-ms        (rt/settings [:session-ttl-ms])
          post-login    (rt/settings [:post-login-redirect])
          redirect-to   (or next post-login)

          ;; already auth'd?
          cookie-token  (get-in request [:cookies cookie :value])
          already       (when cookie-token
                          (authenticate-browser-token cookie-token))
          ;; catalog lookup
          user-id       (when token!! (cat/lookup-user-id (rt/catalog) token!! request))
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
             cookie-attrs   (assoc (rt/cookie-attrs request) :max-age max-age)]
         (sess/put-entry! (rt/store) session-token {:user-id username :expires-at expires-at})
         (-> (resp/redirect redirect-to 303) ;; See Other after POST
             (resp/set-cookie cookie session-token cookie-attrs)
             cc/with-nostore))

       ;; Bad credentials → bounce back to login with error + next
       :else
       (let [login (have string? (rt/settings [:login-url]))
             loc   (str login
                        "?error=bad_credentials"
                        (when redirect-to (str "&next=" (rcodec/url-encode redirect-to))))]
         (-> (resp/redirect loc 303)
             cc/with-nostore))))))

(defn logout-handler
  "POST handler Clear cookie + session and redirect to a confirmation page."
  [request]
  (rt/require-runtime)
  (with-schema s/EmptyRequest request
   (let [cookie   (rt/settings [:cookie-name])
         redirect (rt/settings [:post-logout-redirect])]
     (when-let [token (get-in request [:cookies cookie :value])]
       (sess/delete-entry! (rt/store) token))
     (let [cookie-settings  (rt/cookie-attrs request)]
       (-> (resp/redirect redirect 303) ;; See Other after POST
           (resp/set-cookie cookie "" (clear-cookie-attrs cookie-settings))
           cc/with-nostore)))))
