(ns turvata.core
  (:require
   [clojure.string :as string]
   [turvata.codec :as codec]
   [turvata.crypto :as crypto]
   [turvata.catalog :as cat])
  (:import
   (clojure.lang ExceptionInfo)
   (java.time Instant)))

(set! *warn-on-reflection* true)

(def parse-token!! codec/parse-token!!)
(def generate-token!! codec/generate-token!!)

(defn- unexpired? [^Instant now ^Instant expires-at]
  (if (nil? expires-at)
    true ;; Tokens without an expiration are immortal
    (.isBefore now expires-at)))

(defn verify-key
  "Evaluates a parsed token against its database state to determine validity.
   Returns :valid/primary, :valid/grace, or false."
  [^bytes pepper!! token-map db-row ^Instant now]
  (let [computed-hash!! (crypto/hash-key pepper!! token-map)
        token-version   (int (:rotation-version token-map))
        db-version      (int (:rotation-version db-row))

        ;; 1. Check Primary Match
        primary-match?
        (and (= token-version db-version)
             (crypto/constant-time-eq? computed-hash!! ^bytes (:hash db-row))
             (unexpired? now (:expires-at db-row)))

        ;; 2. Check Grace Match for Zero-Downtime Rotation
        grace-match?
        (and (:prev-hash db-row)
             (:grace-period-expires-at db-row)
             (= token-version (dec db-version))
             (crypto/constant-time-eq? computed-hash!! ^bytes (:prev-hash db-row))
             (unexpired? now (:grace-period-expires-at db-row)))]

    (cond
      primary-match? :valid/primary
      grace-match?   :valid/grace
      :else          false)))

(defn- parse-token-or-nil
  [raw-token!!]
  (try
    (codec/parse-token!! raw-token!!)
    (catch ExceptionInfo e
      ;; auth errors throw 400
      (if (= 400 (:status (ex-data e)))
        nil
        ;; code bug, catalog outage, etc.  throw -> 500 from error MW
        (throw e)))))

(defn authenticate-api-token
  "returns user-id or nil"
  [raw-token!! env request]
  (when-let [token-map (parse-token-or-nil raw-token!!)]
    (let [prefix  (some-> (get-in env [:settings :prefix]) string/lower-case)
          user-id (:user-id token-map)]
      (when (= prefix (:prefix token-map))
        (let [db-row (cat/lookup-record (:catalog env) user-id request)
              pepper (get-in env [:settings :pepper])
              now    (Instant/now)]
          (when (and db-row (verify-key pepper token-map db-row now))
            user-id))))))
