(ns turvata.keys
  (:require
   [taoensso.truss :refer [have]])
  (:import
   (java.util Base64 Base64$Encoder)
   (java.security SecureRandom)))

(set! *warn-on-reflection* true)

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; helpers

(def ^:private ^SecureRandom
  secure-random (SecureRandom.))

(def ^:private ^Base64$Encoder
  b64-encoder (.withoutPadding (Base64/getUrlEncoder)))

(defn- bytes->b64-str
  ^String [^bytes byte-arr]
  (.encodeToString b64-encoder byte-arr))

(defn- gen-nonce
  "Generate a random nonce of size `bytes`."
  ^bytes [^long bytes]
  (let [nonce (byte-array bytes)]
    (.nextBytes secure-random nonce)
    nonce))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; public API

(defn generate-session-token []
  (let [bytes (gen-nonce 32)]
    (bytes->b64-str bytes)))
