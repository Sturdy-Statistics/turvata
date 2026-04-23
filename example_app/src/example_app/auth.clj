(ns example-app.auth
  (:require
   [turvata.session :as sess]
   [turvata.settings :as settings]
   [turvata.catalog :as cat]
   [turvata.codec :as codec]
   [turvata.crypto :as crypto])
  (:import
   (java.nio.charset StandardCharsets)))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; important settings

(defn login-url [] "/auth/login")
(defn logout-url [] "/auth/logout")
(defn post-login-redirect [] "/admin")
(defn post-logout-redirect [] "/auth/logout/success")

;; The required 32+ byte cryptographic pepper for the V2 HMAC engine.
;; In production, load this from a secure environment variable or KMS.
(def dev-pepper (.getBytes "ThisIsA32ByteLongSecretPepperDev" StandardCharsets/UTF_8))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; turvata config

(defonce store (sess/in-memory-store))

(defn build-catalog []
  ;; To make the example app runnable out of the box without manual crypto math,
  ;; we generate a fresh V2 token on startup and seed the in-memory catalog.
  (let [alice-uuid (random-uuid)
        token-str  (codec/generate-token!! {:prefix "demo" :rotation-version 1 :user-id alice-uuid})
        parsed     (codec/parse-token!! token-str)
        token-hash (crypto/hash-key dev-pepper parsed)]

    (println "\n=======================================================")
    (println "DEMO APP STARTED. USE THESE CREDENTIALS TO LOG IN:")
    (println "User ID (UUID):" alice-uuid)
    (println "V2 API Token:  " token-str)
    (println "=======================================================\n")

    (cat/in-memory-catalog
     {alice-uuid {:hash token-hash :rotation-version 1}})))

(defn prune-expired-sessions! []
  (sess/prune-expired! store (sess/now-ms)))

(defn turvata-settings []
  {:pepper               dev-pepper
   :prefix               "demo"
   :cookie-name          "turvata_session"
   :session-ttl-ms       (* 4 3600 1000)  ; 4 hours
   :login-url            (login-url)
   :post-login-redirect  (post-login-redirect)
   :post-logout-redirect (post-logout-redirect)
   :http-only?           true
   :same-site            :lax})

(defn make-turvata-env []
  {:settings (settings/normalize (turvata-settings))
   :catalog  (build-catalog)
   :store    store})
