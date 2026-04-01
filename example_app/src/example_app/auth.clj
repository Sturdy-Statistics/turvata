(ns example-app.auth
  (:require
   [turvata.session :as sess]
   [turvata.settings :as settings]
   [turvata.catalog]

   [sturdy.fs :as sfs]))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; important settings

(defn login-url [] "/auth/login")
(defn logout-url [] "/auth/logout")
(defn post-login-redirect [] "/admin")
(defn post-logout-redirect [] "/auth/logout/success")

(defn token-file [] "secrets/authorized_users_debug.edn")

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; turvata config

(defonce store (sess/in-memory-store))

(defn build-catalog [token-file]
  ;; EDN format: {"<hashed-token (b64sha256)>" <user-id (string)>"}
  (turvata.catalog/hashed-map-catalog
   (sfs/slurp-edn token-file)))

;;; now returns a count
(defn prune-expired-sessions! []
  (sess/prune-expired! store (sess/now-ms)))

(defn turvata-settings []
  (let [cookie-name "turvata_session"
        session-ttl-ms (* 4 3600 1000)  ; 4 hours
        login-url (login-url)
        post-login-redirect (post-login-redirect)
        post-logout-redirect (post-logout-redirect)
        http-only? true
        same-site :lax]
    (cond-> {}
      cookie-name (assoc :cookie-name cookie-name)
      session-ttl-ms (assoc :session-ttl-ms session-ttl-ms)
      login-url (assoc :login-url login-url)
      post-login-redirect (assoc :post-login-redirect post-login-redirect)
      post-logout-redirect (assoc :post-logout-redirect post-logout-redirect)
      http-only? (assoc :http-only? http-only?)
      same-site (assoc :same-site same-site))))

(defn make-turvata-env []
  {:settings (settings/normalize (turvata-settings))
   :catalog  (build-catalog (token-file))
   :store    store})
