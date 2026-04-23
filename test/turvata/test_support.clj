(ns turvata.test-support)

(set! *warn-on-reflection* true)

(def test-pepper-bytes (byte-array (repeat 32 (byte 42))))

(def test-settings
  {:pepper test-pepper-bytes
   :prefix "sturdy-test"})
