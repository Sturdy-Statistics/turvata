(ns turvata.codec
  (:require
   [clojure.string :as str]
   [turvata.util :as u]
   [taoensso.truss :refer [have]])
  (:import
   (java.security SecureRandom)
   (java.nio ByteBuffer)
   (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:private ^SecureRandom secure-random (SecureRandom.))

(defn- nonce
  ^bytes [^long length]
  (let [iv (byte-array length)]
    (.nextBytes secure-random iv)
    iv))

(defn parse-token!!
  "Parses a V2 token string into its structural map.
   Fails fast on checksum mismatch."
  [raw-token!!]
  (try
    ;; Normalize the entire token via lowercase conversion, no whitespace trimming
    (let [normalized-token  (str/lower-case raw-token!!)
          parts             (str/split normalized-token #"_")]

      (when-not (= 3 (count parts))
        (u/throw-400!))

      (let [[prefix versions-str encoded-payload!!] parts

            spec-version      (Integer/parseInt (subs versions-str 0 2) 16)
            rotation-version  (Integer/parseInt (subs versions-str 2 6) 16)

            payload-bytes     (u/b32->bytes ^String encoded-payload!!)

            ;; V2 expects exactly 52 bytes
            raw-data!!        (Arrays/copyOfRange payload-bytes  0 48)
            expected-checksum (-> (Arrays/copyOfRange payload-bytes 48 52)
                                  u/bytes->checksum)]

        (when-not (= 2 spec-version) ;; Enforce V2 explicitly
          (u/throw-400!))

        (u/verify-checksum! raw-data!! expected-checksum)

        (let [user-id-bytes (Arrays/copyOfRange raw-data!! 0 16)
              user-id-uuid  (u/bytes->uuid user-id-bytes)
              secret!!      (Arrays/copyOfRange raw-data!! 16 48)]

          {:prefix           prefix
           :spec-version     spec-version
           :rotation-version rotation-version
           :user-id          user-id-uuid
           :secret           secret!!
           :checksum         expected-checksum})))
    (catch Exception _
      (u/throw-400!))))

(defn generate-token!!
  "Generates a structurally sound V2 token string and payload."
  [{:keys [prefix rotation-version user-id]}]
  (let [prefix            (have string? prefix)
        user-id-bytes     (u/uuid->bytes (have uuid? user-id))
        secret!!          (nonce 32)

        raw-data!!        (let [buffer (ByteBuffer/allocate 48)]
                            (.put buffer ^bytes user-id-bytes)
                            (.put buffer ^bytes secret!!)
                            (.array buffer))

        checksum-bytes    (-> (u/compute-crc32 raw-data!!)
                              u/checksum->bytes)

        payload-bytes!!   (let [buffer (ByteBuffer/allocate 52)]
                            (.put buffer ^bytes raw-data!!)
                            (.put buffer ^bytes checksum-bytes)
                            (.array buffer))

        payload!!         (u/bytes->b32 payload-bytes!!)

        ;; V2 explicitly hardcoded
        versions-str      (format "%02x%04x" 2 (have nat-int? rotation-version))]

    (str prefix "_" versions-str "_" payload!!)))
