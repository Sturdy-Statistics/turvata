(ns turvata.test-support)

(def test-pepper-bytes (byte-array (repeat 32 (byte 42))))

(def test-settings
  {:pepper test-pepper-bytes
   :prefix "sturdy-test"})
