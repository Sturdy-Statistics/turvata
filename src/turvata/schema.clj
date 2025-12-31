(ns turvata.schema
  (:require
   [clojure.string :as string]
   [sturdy.malli-firewall.core :as f]))

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

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def SettingsIn
  [:map {:closed true}
   [:cookie-name          {:optional true} NonBlankString]
   [:session-ttl-ms       {:optional true} PosInteger]
   [:login-url            {:optional true} RelativeURI]
   [:post-login-redirect  {:optional true} RelativeURI]
   [:post-logout-redirect {:optional true} RelativeURI]
   [:http-only?           {:optional true} boolean?]
   [:same-site            {:optional true} [:enum :none :lax :strict]]
   [:https?               {:optional true} [:-> :map :boolean]]])

(def Settings
  [:map {:closed true}
   [:cookie-name           NonBlankString]
   [:session-ttl-ms        PosInteger]
   [:login-url             RelativeURI]
   [:post-login-redirect   RelativeURI]
   [:post-logout-redirect  RelativeURI]
   [:http-only?            boolean?]
   [:same-site             [:enum :none :lax :strict]]
   [:https?                [:-> :map :boolean]]])

(def TurvataLogin
  [:map {:closed true}
   [:username NonBlankString]
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
