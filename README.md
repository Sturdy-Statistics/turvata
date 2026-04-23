## turvata

[![Clojars Project](https://img.shields.io/clojars/v/com.sturdystats/turvata.svg)](https://clojars.org/com.sturdystats/turvata)

**Verb** ([Finnish](https://translate.google.com/?sl=fi&tl=en&text=turvata&op=translate)):
secure, safeguard, ensure, assure, defend, indemnify, insure, cover

**Strict, explicit authentication boundaries for Clojure Ring applications.**

`turvata` provides a small set of primitives for:

- **API token authentication** (via `Authorization: Bearer <token>`)
- **Browser session authentication** (via signed cookies + server-side session store)
- A simple **closure-based environment model** (allowing multiple isolated auth zones per app)
- Safe defaults, explicit failure modes, and minimal abstraction

It is designed for **internal tools, admin portals, and service-to-service communication**, not consumer-facing auth.

Turvata was originally developed to meet the internal security and operational requirements of **Sturdy Statistics**.
It is published as open source to support transparency, auditability, and reuse, but its design is intentionally conservative and driven by real production needs.
We may not accept feature requests that dilute its focus.

> [!WARNING]
> **NOTE** v0.3.0 represents a severe breaking change from v0.2.x.
> `turvata` abandoned simple SHA-256 token hashes in favor of cryptographically bound HMAC-SHA512 tokens with structural context. All `user-id`s must now be `java.util.UUID`. V1 tokens are no longer supported.
>
> **NOTE** v0.2.0 represents a breaking change from v0.1.x.
> `turvata` moved from a runtime-based model to a closure-based model

## Design goals

- **Explicit**:
  Configuration and authentication behavior should be easy to read in code.

- **Deny by default**:
  Missing or invalid credentials never authenticate.

- **Defense in Depth**:
  Database compromises should not lead to system breaches.  Crypto logic is isolated from database storage.

- **High-entropy secrets**:
  Tokens are assumed to be random secrets, not user-chosen passwords.

- **Closed World Validation**:
  Malformed data is dropped immediately.  The system only reasons about data it explicitly expects.

- **Zero-Downtime Rotation**:
  Built-in support for sliding grace periods across credential rotations.

- **Minimal dependencies and configuration**

## Non-goals

`turvata` does **not** provide:

- OAuth / OpenID Connect
- Password hashing or user-chosen passwords
- User management, signup, or recovery
- CSRF protection (delegate to your app)
- Distributed session replication (pluggable store only)

## Installation

Add to `deps.edn`:

```clojure
{:deps {com.sturdystats/turvata {:mvn/version "VERSION"}}}
```

## Core concepts

### Tokens

- API Tokens are **high-entropy, 52-byte structures** encoding an opaque UUID, a rotation version, a secure random secret, and a CRC32 checksum.
- Tokens are **cryptographically bound** using a backend pepper. An attacker with full database write access cannot bypass authentication because they do not possess the memory-bound pepper.
- The server stores the expected hashes, rotation versions, and expiration timestamps.
- Token lookup is strictly `user-id-uuid → database-record`.

Generate tokens using `turvata.codec/generate-token!!`:

```clojure
(require '[turvata.codec :as codec])

(codec/generate-token!! {:prefix "svc" :rotation-version 1 :user-id #uuid "..."})
;; => "svc_020001_BASE32STRING..."
```

Store the token in the client.
Compute the expected hash using `turvata.crypto/hash-key` to store in your catalog.

### Token catalogs

A `TokenCatalog` maps a `user-id` UUID to a database record map.
Because the crypto runs in memory after the database lookup, catalogs are strictly responsible for data retrieval, not evaluation.

Provided implementations:

```clojure
(require '[turvata.catalog :as cat])

(cat/in-memory-catalog
  {#uuid "..." {:hash #bytes "..." :rotation-version 1}})

(cat/edn-file-catalog "tokens.edn")

(cat/composite [catalog-a catalog-b])
```

### Session store

Browser sessions are ephemeral, stateful, and stored server-side via a `SessionStore`.
They use simple, secure 32-byte strings rather than full V2 API tokens.

Provided implementation:

```clojure
(require '[turvata.session :as sess])

(sess/in-memory-store)
```

## Environment configuration

`turvata` uses a functional, closure-based architecture to avoid hidden global state. 
You configure an "environment" map and pass it to middleware and handler factories. 
This allows you to run completely isolated authentication zones (e.g., a Web Admin portal and a Public API) in the same application.

**V2 requires a high-entropy `byte[]` pepper and a token prefix.**

```clojure
(require
  '[turvata.settings :as settings]
  '[turvata.session :as sess]
  '[turvata.catalog :as cat])

(def pepper-bytes (byte-array ...)) ;; Must be at least 32 bytes

(def web-env
  {:settings (settings/normalize {:pepper pepper-bytes
                                  :prefix "myapp"
                                  :cookie-name "myapp-session"
                                  :session-ttl-ms (* 4 60 60 1000)
                                  :login-url "/login"})
   :catalog  my-token-catalog
   :store    (sess/in-memory-store)})
```

## Ring middleware

Middleware functions are created by calling a factory with your environment map.

### API authentication

```clojure
(require '[turvata.ring.middleware :as mw])

(def require-api-auth (mw/require-api-auth api-env))

(require-api-auth handler)
```

- Expects `Authorization: Bearer <v2-token>`
- Fails fast on bad checksums, malformed strings, or missing peppers.
- On success: associates `:user-id` (a `java.util.UUID`) in the request.
- On failure: returns `401 Unauthorized` with `no-store` headers.

### Web (session) authentication

```clojure
(def require-web-auth (mw/require-web-auth web-env))

(require-web-auth handler)
```

- On success: associates `:user-id` in the request.
- Refreshes the cookie when nearing expiry (sliding sessions).
- On failure: aggressively clears the cookie and redirects to `:login-url` with `?next=...`.

## Login and logout handlers

Handlers are also created via factories.

### Login

```clojure
(require '[turvata.ring.handlers :as h])

(def login-handler (h/make-login-handler web-env))

(login-handler request)
```

Expected form params:

- `username` (Must match the `user-id` UUID embedded in the provided token)
- `token` (A valid V2 API Token)
- `next` (optional, relative path)

On success, validates the V2 token and mints a stateful browser session cookie.

### Logout

```clojure
(def logout-handler (h/make-logout-handler web-env))

(logout-handler request)
```

## Example App

See the directory `example_app` for a small, runnable Ring application demonstrating a complete login + admin flow using turvata.
The README file in that directory explains how to run and use the app.

## Security notes

These notes describe the intended security model and assumptions of `turvata`.

- **Closed World Validation:** Uses `malli` to enforce rigorous schemas at the edge. Unknown keys are stripped, malformed inputs are rejected immediately.
- **Context Binding:** Hashes are bound to the `user-id` and `rotation-version` to prevent database swapping.
- **Constant Time Evaluation:** Uses `MessageDigest/isEqual` to mitigate timing attacks.
- **Rollback Resistance:** Grace periods are explicitly limited to `(dec current-version)`.
- Open redirects are structurally prevented.

## License

Apache License 2.0

Copyright © Sturdy Statistics

## Postscript

> **A note to Finnish speakers:**
> We chose the name **turvata** in homage to [metosin](https://github.com/metosin) and out of admiration for the expressiveness of the Finnish language.
> We’re not Finnish speakers, so if we’ve misused the term, we apologize.


<!-- Local Variables: -->
<!-- fill-column: 1000000 -->
<!-- End: -->
