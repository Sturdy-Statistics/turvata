(ns turvata.ring.util
  (:require
   [taoensso.truss :refer [have]]))

(set! *warn-on-reflection* true)

(def ^:private expired-http-date
  ;; RFC 7231 IMF-fixdate, safely in the past
  "Thu, 01 Jan 1970 00:00:00 GMT")

(defn clear-cookie-attrs [cookie-attrs-map]
  (assoc cookie-attrs-map
         :max-age 0
         :expires expired-http-date))

(defn ms->s [ms] (quot (long ms) 1000))

(defn cookie-attrs [settings request]
  {:path "/"
   :http-only (have boolean? (:http-only? settings))
   :same-site (have keyword? (:same-site settings))
   :secure    ((:https? settings) request)})
