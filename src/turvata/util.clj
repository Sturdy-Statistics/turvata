(ns turvata.util
  (:require
   [clojure.string :as string])
  (:import
   (java.util Arrays UUID HexFormat)
   (java.util.zip CRC32)
   (java.nio ByteBuffer)
   (org.apache.commons.codec.binary Base32)))

(set! *warn-on-reflection* true)

(defn throw-400! []
  (throw (ex-info "Bad Request"
                  {:status 400
                   :message "Malformed or incomplete Turvata token."})))

;; Thread-safe reusable codec instance
(def ^:private ^Base32 base32-codec (Base32.))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; checksums

(defn compute-crc32
  [^bytes payload]
  (let [crc (CRC32.)]
    (.update crc payload)
    (.getValue crc)))

(defn bytes->checksum ^long [^bytes checksum-bytes]
  (-> (ByteBuffer/wrap checksum-bytes)
      (.getInt)
      (bit-and 0xFFFFFFFF)))

(defn checksum->bytes ^bytes [^long checksum]
  (-> (ByteBuffer/allocate 4)
      (.putInt (unchecked-int checksum))
      (.array)))

(defn verify-checksum!
  [^bytes raw-bytes ^long expected-checksum]
  (let [actual (compute-crc32 raw-bytes)]
    (when-not (= actual expected-checksum)
      (throw-400!))))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; UUID

(defn bytes->uuid
  "Reconstructs a java.util.UUID from a 16-byte array."
  ^UUID [^bytes uuid-bytes]
  (let [buffer (ByteBuffer/wrap uuid-bytes)
        msb    (.getLong buffer)
        lsb    (.getLong buffer)]
    (UUID. msb lsb)))

(defn uuid->bytes
  "Serializes a java.util.UUID into a 16-byte array."
  ^bytes [^UUID uuid]
  (let [buffer (ByteBuffer/allocate 16)]
    (.putLong buffer (.getMostSignificantBits uuid))
    (.putLong buffer (.getLeastSignificantBits uuid))
    (.array buffer)))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; base32

(defn b32->bytes
  ^bytes [^String b32-str!!]
  (let [;; Normalization strictly focuses on lowercase conversion.
        ;; No whitespace trimming applied.
        normalized        (string/lower-case b32-str!!)
        ;; Commons Codec requires uppercase for internal decoding operations
        upper             (string/upper-case normalized)
        payload-len       (count upper)
        padding-needed    (let [rem (mod payload-len 8)]
                            (if (zero? rem) 0 (- 8 rem)))
        padded-payload!!  (str upper (apply str (repeat padding-needed "=")))]
    (.decode base32-codec ^String padded-payload!!)))

(defn bytes->b32
  ^String [^bytes payload-bytes!!]
  (-> (.encodeAsString base32-codec payload-bytes!!)
      (string/replace "=" "")
      (string/lower-case)))
