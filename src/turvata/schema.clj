(ns turvata.schema
  (:require
   [clojure.string :as string]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn- keywordize
  [x]
  (if (map? x) (walk/keywordize-keys x) x))

(defn ends-with?
  "Returns a predicate (string? && ends-with ext)."
  [ext]
  (fn [s] (and (string? s) (string/ends-with? s ext))))

(defn ends-with-ext
  "Schema form for 'string ending with ext' with a clear error message."
  [ext]
  [:fn {:error/message (str "must end with " ext)}
   (ends-with? ext)])

(defn relative-uri?
  [s]
  (and
   (string? s)
   (seq s)
   (string/starts-with? s "/")))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def NonBlankString
  [:and string? [:fn {:error/message "must not be blank"} (complement string/blank?)]])

(def NonNegInteger
  [:and integer? [:fn {:error/message "must be non-negative"} (complement neg?)]])

(def PosInteger
  [:and integer? [:fn {:error/message "must be non-negative"} pos?]])

(def RelativeURI
  [:fn {:error/message "must be a relative URI starting with '/'"} relative-uri?])

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

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; validation

(def decode-transformer
  "Decodes common string values (\"42\"→42, \"true\"→true)."
  (mt/transformer
   mt/string-transformer
   mt/default-value-transformer))

(defn coerce
  "Decode raw (usually stringly) params into typed data using the shared transformer.
   Returns the decoded (but not yet validated) map.
   Note: also keywordizes map keys. See `validate` to both coerce & check."
  [schema params]
  (m/decode schema (keywordize params) decode-transformer))

(defn validate
  "Coerce + validate in one step.
   Returns {:ok data} or {:error {:message .. :problems ..}}.
   Problems are humanized Malli errors suitable for display/logging."
  [schema params]
  (let [coerced (coerce schema params)]
    (if (m/validate schema coerced)
      {:ok coerced}
      (let [expl (->> coerced
                      (m/explain schema)
                      (me/with-spell-checking)
                      (me/humanize))]
        {:error {:message "Invalid request parameters"
                 :problems expl}}))))

(defn assert-valid!
  "Like `validate`, but throws (ex-info) on error and returns data on success."
  [schema params]
  (let [res (validate schema params)]
    (if (:error res)
      (throw (ex-info "Bad request" (:error res)))
      (:ok res))))
