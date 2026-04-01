## turvata

[![Clojars Project](https://img.shields.io/clojars/v/com.sturdystats/turvata.svg)](https://clojars.org/com.sturdystats/turvata)

**Verb** ([Finnish](https://translate.google.com/?sl=fi&tl=en&text=turvata&op=translate)):
secure, safeguard, ensure, assure, defend, indemnify, insure, cover

**Minimal, explicit authentication helpers for Clojure Ring applications.**

`turvata` provides a small set of primitives for:

- **API token authentication** (via `Authorization: Bearer <token>`)
- **Browser session authentication** (via signed cookies + server-side session store)
- A simple **closure-based environment model** (allowing multiple isolated auth zones per app)
- Safe defaults, explicit failure modes, and minimal abstraction

It is designed for **internal tools and admin portals**, not consumer-facing auth.

Turvata was originally developed to meet the internal security and operational requirements of **Sturdy Statistics**.
It is published as open source to support transparency, auditability, and reuse, but its design is intentionally conservative and driven by real production needs.
We may not accept feature requests that dilute its focus.

> [!WARNING]
> **NOTE** v0.2.0 represents a breaking change from v0.1.x.
> `turvata` moved from a runtime-based model to a closure-based model

## Design goals

- **Explicit**:
  Authentication behavior should be easy to read in code.

- **Deny by default**:
  Missing or invalid credentials never authenticate.

- **High-entropy secrets**:
  Tokens are assumed to be random secrets, not user-chosen passwords.

- **Minimal dependencies and configuration**

## Non-goals

`turvata` does **not** provide:

- OAuth / OpenID Connect
- Password hashing or password login
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

- Tokens are **high-entropy random strings**.
- The server stores **only hashes** of tokens.
- Clients present raw tokens.
- Token lookup is `token → user-id`.

Generate tokens using `turvata.keys/generate-token`:

```clojure
(require '[turvata.keys :as keys])

(keys/generate-token)
;; => {:token "...", :hashed "..."}
```

Store `:hashed` in your catalog; give `:token` to the client.

### Token catalogs

A `TokenCatalog` maps a bearer token to a user identifier.
Tokens are hashed before lookup; catalogs should store hashes, not raw tokens (except in tests).

Provided implementations:

```clojure
(require '[turvata.catalog :as cat])

(cat/hashed-map-catalog
  {"<hashed-token>" "alice"})

(cat/plain-map-catalog
  {"raw-token" "alice"})

(cat/edn-file-catalog "tokens.edn")

(cat/composite [catalog-a catalog-b])
```

### Session store

Browser sessions are stored server-side via a `SessionStore`.

Provided implementation:

```clojure
(require '[turvata.session :as sess])

(sess/in-memory-store)
```

## Environment configuration

`turvata` uses a functional, closure-based architecture to avoid hidden global state. 
You configure an "environment" map and pass it to middleware and handler factories. 
This allows you to run completely isolated authentication zones (e.g., a Web Admin portal and a Public API) in the same application.

```clojure
(require
  '[turvata.settings :as settings]
  '[turvata.session :as sess]
  '[turvata.catalog :as cat])

(def web-env
  {:settings (settings/normalize {:cookie-name "myapp-session"
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

(def require-api-auth (mw/wrap-api-auth api-env))

(require-api-auth handler)
```

- Expects `Authorization: Bearer <token>`
- On success: associates `:user-id` in the request
- On failure: returns `401 Unauthorized`

### Web (session) authentication

```clojure
(def require-web-auth (mw/wrap-web-auth web-env))

(require-web-auth handler)
```

- On success: associates `:user-id` in the request
- Refreshes the cookie when nearing expiry
- On failure: redirects to `:login-url` with `?next=...`

## Login and logout handlers

Handlers are also created via factories.

### Login

```clojure
(require '[turvata.ring.handlers :as h])

(def login-handler (h/make-login-handler web-env))

(login-handler request)
```

Expected form params:

- `username`
- `token`
- `next` (optional, relative path)

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

- Tokens **must be high-entropy random secrets**
- Token hashes use SHA-256
- Open redirects are prevented
- Forwarded HTTPS headers should only be trusted behind a proxy

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
