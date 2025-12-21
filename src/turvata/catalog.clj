(ns turvata.catalog
  (:require
   [turvata.keys :as k]
   [sturdy.fs :as sfs]))

(defprotocol TokenCatalog
  (lookup-user-id [this token]
    "Return user-id (string/keyword) if bearer token is valid, else nil."))

(defn hashed-map-catalog
  "Catalog backed by an in-memory map keyed by hashed tokens.

  Provide {<hash> -> <user-id>} where <hash> matches (k/hash-token token).
  This avoids storing raw tokens in memory.

  Tokens must be high-entropy (random), not user-chosen."
  [hashed->user-id]
  (reify TokenCatalog
    (lookup-user-id [_ token]
      (when (string? token)
        (get hashed->user-id (k/hash-token token))))))

(defn plain-map-catalog
  "Catalog backed by an in-memory map keyed by raw tokens (useful in tests).

  Provide {<raw-token> -> <user-id>}."
  [token->user-id]
  (reify TokenCatalog
    (lookup-user-id [_ token]
      (when (string? token)
        (get token->user-id token)))))

(defn fn-catalog
  "Wrap an arbitrary (fn [token] -> user-id|nil)."
  [f]
  (reify TokenCatalog
    (lookup-user-id [_ token]
      (when (string? token) (f token)))))

(defn composite
  "Try multiple catalogs in order; return the first non-nil user-id."
  [catalogs]
  (let [catalogs' (remove nil? catalogs)]
    (reify TokenCatalog
      (lookup-user-id [_ token]
        (some #(lookup-user-id % token) catalogs')))))

(defn edn-file-catalog
  "EDN-backed catalog.

  File format: a vector of maps [{:hashed \"...\" :user-id \"alice\"} ...].
  Note: reloads the file on each call; intended for low traffic and small catalogs."
  [path]
  (reify TokenCatalog
    (lookup-user-id [_ token]
      (when (string? token)
        (let [h (k/hash-token token)
              rows (sfs/slurp-edn path)]
          (some (fn [{:keys [hashed user-id]}]
                  (when (= hashed h) user-id))
                rows))))))

(defn valid-token?
  "Return true if token is valid in catalog, else false."
  [catalog token]
  (boolean (lookup-user-id catalog token)))
