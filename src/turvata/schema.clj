(ns turvata.schema
  (:require
   [clojure.string :as string]
   [sturdy.malli-firewall.core :as f]))

(set! *warn-on-reflection* true)

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn relative-uri?
  [s]
  (and
   (string? s)
   (seq s)
   (string/starts-with? s "/")
   (not (string/starts-with? s "//"))))

(def NonBlankString
  [:and string? [:fn {:error/message "must not be blank"} (complement string/blank?)]])

(def NonNegInteger
  [:and integer? [:fn {:error/message "must be non-negative"} (complement neg?)]])

(def PosInteger
  [:and integer? [:fn {:error/message "must be non-negative"} pos?]])

(def RelativeURI
  [:fn {:error/message "must be a relative URI starting with '/'"} relative-uri?])

(def TokenPrefix
  [:and string?
   [:re {:error/message "must contain only alphanumeric characters and hyphens to prevent parsing errors"}
    #"^[a-zA-Z0-9\-]+$"]])

(def PepperBytes
  [:and bytes?
   [:fn {:error/message "must be at least 32 bytes for sufficient HMAC-SHA512 entropy"}
    #(>= (alength ^bytes %) 32)]])

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def SettingsIn
  [:map {:closed true}
   ;; These two are explicitly REQUIRED by the user on startup
   [:pepper                 PepperBytes]
   [:prefix                 TokenPrefix]

   [:cookie-name            {:optional true} NonBlankString]
   [:session-ttl-ms         {:optional true} PosInteger]
   [:login-url              {:optional true} RelativeURI]
   [:post-login-redirect    {:optional true} RelativeURI]
   [:post-logout-redirect   {:optional true} RelativeURI]
   [:http-only?             {:optional true} boolean?]
   [:same-site              {:optional true} [:enum :none :lax :strict]]
   [:https?                 {:optional true} [:-> :map :boolean]]])

(def Settings
  [:map {:closed true}
   [:pepper                 PepperBytes]
   [:prefix                 TokenPrefix]
   [:cookie-name            NonBlankString]
   [:session-ttl-ms         PosInteger]
   [:login-url              RelativeURI]
   [:post-login-redirect    RelativeURI]
   [:post-logout-redirect   RelativeURI]
   [:http-only?             boolean?]
   [:same-site              [:enum :none :lax :strict]]
   [:https?                 [:-> :map :boolean]]])

(def TurvataLogin
  [:map {:closed true}
   [:username :uuid]
   [:token    NonBlankString]

   [:next     {:optional true} RelativeURI]
   [:error    {:optional true} string?]

   [:__anti-forgery-token {:optional true} string?]])

(def EmptyRequest
  [:map {:closed true}
   [:__anti-forgery-token {:optional true} string?]])

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; validation

(defn assert-valid!
  [schema params]
  (f/assert-valid! schema params {:strip-unknown-keys? false}))
