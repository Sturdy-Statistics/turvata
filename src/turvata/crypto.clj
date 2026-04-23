(ns turvata.crypto
  (:require
   [turvata.util :as u])
  (:import
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)
   (java.security MessageDigest)
   (java.nio ByteBuffer)
   (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn hmac-sha512
  "Computes an HMAC-SHA512 over the raw data bytes using the backend pepper."
  ^bytes [^bytes pepper!! ^bytes data!!]
  (let [mac (Mac/getInstance "HmacSHA512")
        key-spec (SecretKeySpec. pepper!! "HmacSHA512")]
    (.init mac key-spec)
    (.doFinal mac data!!)))

(defn constant-time-eq?
  "Securely compares two byte arrays in constant time to prevent timing attacks."
  [^bytes a ^bytes b]
  (MessageDigest/isEqual a b))

(defn hash-key
  "Computes the final cryptographically bound HMAC-SHA512 hash.
   Binds the token to its specific structural context to prevent DB-level swaps."
  ^bytes [^bytes pepper!! token-map]
  (let [user-id-bytes    (u/uuid->bytes (:user-id token-map))
        rotation-version (int (:rotation-version token-map))
        secret!!         ^bytes (:secret token-map)
        magic!!          (.getBytes "TRV2" StandardCharsets/UTF_8)

        ;; Buffer Size:
        ;; 4 (magic) + 16 (user-id) + 2 (rotation-version) + 32 (secret) = 54 bytes
        buffer (ByteBuffer/allocate 54)]

    (.put buffer ^bytes magic!!)
    (.put buffer ^bytes user-id-bytes)
    (.putShort buffer (short rotation-version))
    (.put buffer secret!!)

    (hmac-sha512 pepper!! (.array buffer))))
