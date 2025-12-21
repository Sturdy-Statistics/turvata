(ns turvata.session)

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
