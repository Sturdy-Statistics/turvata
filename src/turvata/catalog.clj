(ns turvata.catalog
  (:require
   [sturdy.fs :as sfs]
   [turvata.util :as u]))

(set! *warn-on-reflection* true)

(defprotocol TokenCatalog
  (lookup-record
    [this user-id-uuid]
    [this user-id-uuid context]
    "Returns the database record map for the given user-id UUID, or nil if not found.
     The returned map must contain:
     - :hash (byte array)
     - :rotation-version (integer)
     - :expires-at (java.time.Instant or nil)
     Optional keys for zero-downtime rotation:
     - :prev-hash (byte array)
     - :grace-period-expires-at (java.time.Instant)

     Context is an optional map (e.g. ring request, log data) for auditing."))

(defn in-memory-catalog
  "Catalog backed by an in-memory map keyed by user-id UUIDs.
   Provide {<uuid> -> <db-row-map>}. Useful for testing."
  [uuid->record]
  (reify TokenCatalog
    (lookup-record [_ user-id-uuid]
      (when (uuid? user-id-uuid)
        (get uuid->record user-id-uuid)))

    (lookup-record [this user-id-uuid _context]
      (lookup-record this user-id-uuid))))

(defn fn-catalog
  "Wrap an arbitrary (fn [uuid] -> record|nil)."
  [f]
  (reify TokenCatalog
    (lookup-record [_ user-id-uuid]
      (when (uuid? user-id-uuid) (f user-id-uuid)))

    (lookup-record [_ user-id-uuid context]
      (when (uuid? user-id-uuid) (f user-id-uuid context)))))

(defn composite
  "Try multiple catalogs in order; return the first non-nil record."
  [catalogs]
  (let [catalogs' (remove nil? catalogs)]
    (reify TokenCatalog
      (lookup-record [_ user-id-uuid]
        (some #(lookup-record % user-id-uuid) catalogs'))

      (lookup-record [_ user-id-uuid context]
        (some #(lookup-record % user-id-uuid context) catalogs')))))

(defn edn-file-catalog
  "EDN-backed catalog.
   File format: [{:user-id #uuid \"...\" :hash-hex \"...\" :rotation-version 1 ...} ...]
   Note: reloads the file on each call; intended for low traffic endpoints.
   Automatically converts hex strings to byte arrays for the crypto engine."
  [path]
  (reify TokenCatalog
    (lookup-record [_ user-id-uuid]
      (when (uuid? user-id-uuid)
        (let [rows (sfs/slurp-edn path)]
          (some (fn [row]
                  (when (= (:user-id row) user-id-uuid)
                    ;; Hydrate hex strings into byte arrays for the core logic
                    (cond-> row
                      (:hash-hex row)      (assoc :hash (u/hex-string->bytes (:hash-hex row)))
                      (:prev-hash-hex row) (assoc :prev-hash (u/hex-string->bytes (:prev-hash-hex row))))))
                rows))))

    (lookup-record [this user-id-uuid _context]
      (lookup-record this user-id-uuid))))
