(ns turvata.ring.util)

(def ^:private expired-http-date
  ;; RFC 7231 IMF-fixdate, safely in the past
  "Thu, 01 Jan 1970 00:00:00 GMT")

(defn clear-cookie-attrs [cookie-attrs-map]
  (assoc cookie-attrs-map
         :max-age 0
         :expires expired-http-date))

(defn ms->s [ms] (quot (long ms) 1000))
