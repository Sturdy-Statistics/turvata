(ns turvata.session
  (:require
   [taoensso.truss :refer [have]]))

(set! *warn-on-reflection* true)

(defn now-ms [] (System/currentTimeMillis))

(defprotocol SessionStore
  (get-entry [this token])                ;; -> {:user-id ... :expires-at ...} | nil
  (put-entry! [this token entry])         ;; -> added entry
  (delete-entry! [this token])            ;; -> deleted entry
  (prune-expired! [this now-ms])          ;; -> number of deleted sessions
  (touch! [this token new-expires-at]))   ;; -> updated entry | nil

;; session map looks like
;; {"token-1" {:user-id "alice" :expires-at 123}}

(defn in-memory-store
  "Atom-backed in-memory session store."
  []
  (let [a (atom {})]
    (reify SessionStore

      (get-entry [_ token] (get @a token))

      (put-entry! [_ token entry] (swap! a assoc token entry))

      (delete-entry! [_ token]
        (let [[old _new] (swap-vals! a dissoc token)]
          (get old token)))

      (prune-expired! [_ now-ms]
        (let [now   (long now-ms)
              pred  (fn [[_token-id {:keys [expires-at]}]] (> (long expires-at) now))
              keep  (fn [old] (into {} (filter pred) old))
              [old new] (swap-vals! a keep)]
          (- (count old) (count new))))

      (touch! [_ token new-expires-at]
        (let [new-expires-at (long new-expires-at)
              f (fn [m]
                  (if-let [e (get m token)]
                    (assoc m token (assoc e :expires-at new-expires-at))
                    m))
              [_old new] (swap-vals! a f)]
          (get new token))))))

(defn authenticate-browser-token
  "Authenticate a browser session token.

   Given a session token (e.g. from a cookie), returns a map on success:
   {:user-id <string> :expires-at <ms> :refreshed? <boolean>}

   Authentication succeeds if the token exists and is not expired.
   If session TTL is positive, the expiration is refreshed when remaining TTL
   is at or below 50%. If TTL is non-positive, the token is never refreshed.

   Returns nil if the token is missing, unknown, or expired."
  [env token!!]
  (let [{:keys [store settings]} env
        now                      (now-ms)]
    (when-let [{:keys [user-id expires-at]}
               (and (not-empty token!!)
                    (get-entry store token!!))]
      (when (> expires-at now)
        (let [ttl       (:session-ttl-ms settings)
              remaining (- expires-at now)]
          (if (pos? ttl)
            (if (<= remaining (quot ttl 2))
              ;; refresh
              (let [new-exp (+ now ttl)
                    touched (touch! store token!! new-exp)]
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
