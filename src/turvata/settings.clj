(ns turvata.settings
  (:require
   [clojure.string :as string]
   [turvata.schema :as schema]))

(defn- hdr [req k]
  (some-> req :headers (get k)))

(defn default-https?
  "Heuristic HTTPS detector that works behind common reverse proxies.

  Checks Ring :scheme, then X-Forwarded-* and Front-End-Https headers.
  Security note: only trust forwarded headers when requests come from a trusted
  reverse proxy / load balancer."
  [request]
  (boolean
   (or
    (= :https (:scheme request))
    (some-> (hdr request "x-forwarded-proto") string/lower-case (= "https"))
    (= "443" (hdr request "x-forwarded-port"))
    (let [ssl (some-> (hdr request "x-forwarded-ssl") string/lower-case)]
      (or (= ssl "on") (= ssl "true")))
    (some-> (hdr request "front-end-https") string/lower-case (= "on")))))

(def default
  {;; session
   :cookie-name "turvata-web-token"
   :session-ttl-ms (* 4 60 60 1000) ; 4h
   ;; POST URIs
   :login-url "/auth/login"
   ;; GET after POST URIs
   :post-login-redirect "/"
   :post-logout-redirect "/auth/logout/success"
   ;; cookie settings
   :http-only? true
   ;; lax is modern default intended for session auth
   :same-site :lax
   :https? default-https?})

(defn normalize
  "Merge user settings with sane defaults and validate.
   Guarantees:
   - :cookie-name is a non-blank string
   - :session-ttl-ms is a non-negative integer
   - :login-url and :post-login-redirect start with \"/\"
   - :https? is a callable predicate"
  [settings-in]
  (schema/assert-valid! schema/SettingsIn settings-in)
  (let [settings  (merge default settings-in)]
    ;; coerce & validate
    (schema/assert-valid! schema/Settings settings)))
