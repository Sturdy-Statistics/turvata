(ns turvata.core
  (:require
   [turvata.runtime :as rt]
   [turvata.session :as sess]
   [taoensso.truss :refer [have]]))

(defn authenticate-browser-token
  "Authenticate a browser session token.

   Given a session token (e.g. from a cookie), returns a map on success:
   {:user-id <string> :expires-at <ms> :refreshed? <boolean>}

   Authentication succeeds if the token exists and is not expired.
   If session TTL is positive, the expiration is refreshed when remaining TTL
   is at or below 50%. If TTL is non-positive, the token is never refreshed.

   Returns nil if the token is missing, unknown, or expired."
  [token]
  (rt/require-runtime)
  (let [now (sess/now-ms)]
    (when-let [{:keys [user-id expires-at]}
               (and (not-empty token) (sess/get-entry (rt/store) token))]
      (when (> expires-at now)
        (let [ttl       (rt/settings [:session-ttl-ms])
              remaining (- expires-at now)]
          (if (pos? ttl)
            (if (<= remaining (quot ttl 2))
              ;; refresh
              (let [new-exp (+ now ttl)
                    touched (sess/touch! (rt/store) token new-exp)]
                {:user-id user-id
                 :expires-at (have integer? (:expires-at touched)) ; read back what store wrote
                 :refreshed? true})
              ;; no refresh
              {:user-id user-id
               :expires-at expires-at
               :refreshed? false})
            ;; ttl <= 0 → never refresh, but still authenticate
            {:user-id user-id
             :expires-at expires-at
             :refreshed? false}))))))
