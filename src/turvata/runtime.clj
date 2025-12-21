(ns turvata.runtime
  (:require
   [turvata.settings :as s]
   [turvata.session :as sess]
   [turvata.catalog :as c]
   [taoensso.truss :refer [have]])
  (:import
   (java.util Map)
   (turvata.session SessionStore)
   (turvata.catalog TokenCatalog)))

(def ^:dynamic *runtime*
  "runtime {:settings {} :catalog ... :session ...}"
  nil)

(defn make-runtime
  [{:keys [^Map settings ^TokenCatalog catalog ^SessionStore store]}]
  (let [settings (s/normalize settings)]
    {:settings settings
     :catalog catalog
     :store store}))

(defn init!
  #_{:clj-kondo/ignore [:unused-binding]}
  [{:keys [^Map settings ^TokenCatalog catalog ^SessionStore store] :as opts}]
  (let [rt (make-runtime opts)]
    (alter-var-root #'*runtime* (constantly rt))))

(defn require-runtime
  []
  (or *runtime*
      (throw (ex-info "turvata not initialized"
                      {:hint "Call (turvata.runtime/init! {:settings ... :catalog ... :store ...})"}))))

(defn settings [ks]
  (get-in (:settings (require-runtime)) ks))

(defn cookie-attrs
  "Compute Ring cookie attrs from settings and request."
  [request]
  {:path "/"
   :http-only (have boolean? (settings [:http-only?]))
   :same-site (have keyword? (settings [:same-site]))
   :secure    ((settings [:https?]) request)})

(defn catalog []
  (:catalog (require-runtime)))

(defn store []
  (:store (require-runtime)))
