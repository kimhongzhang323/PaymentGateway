# KimPay Authentication & Request Signing

Every KimPay API call (except the public actuator probes `/actuator/health` and
`/actuator/info`) must be **authenticated**. Every **mutating** call (any method other
than `GET`/`HEAD`/`OPTIONS`/`TRACE`) must additionally be **signed** and carry
**replay-protection** headers.

The security filter chain runs in this order:

```
ApiKeyAuthFilter  ──▶  RequestSignatureFilter  ──▶  controller
   (who?)                (genuine + fresh?)          (business logic)
```

Sessions are **stateless**: there is no cookie, no server-side session, and no CSRF
token flow.

---

## 1. API key authentication

Each merchant is issued an API key pair via `ApiKeyService.issueKey(merchantId)`:

| Part | Example | Stored as |
|------|---------|-----------|
| Key ID (public) | `pk_test_a1b2c3...` | plaintext, unique |
| Secret | `sk_test_x9y8z7...` | **BCrypt hash only** |

> The secret is shown **once at issuance** and is never retrievable afterward. If lost,
> issue a new key. The plaintext secret is never logged — `SensitiveDataMasker` and the
> logback appenders mask `sk_(test|live)_…` patterns as defense-in-depth.

Send both parts on every request in the `Authorization` header, joined by a colon:

```
Authorization: Bearer <keyId>:<secret>
```

`ApiKeyAuthFilter` looks up the active credential by key ID, verifies the presented
secret against the stored BCrypt hash, and—on success—populates the security context
with a `MerchantPrincipal(merchantId, keyId)` granted `ROLE_MERCHANT`. A missing or
invalid header yields **401**.

---

## 2. Request signing (mutating requests)

In addition to the `Authorization` header, mutating requests carry three signing
headers:

| Header | Meaning |
|--------|---------|
| `X-Kimpay-Timestamp` | Unix epoch **seconds** when the request was signed |
| `X-Kimpay-Nonce` | A unique, single-use random string |
| `X-Kimpay-Signature` | Base64 **RSA SHA256withRSA** signature over the canonical string |

### Canonical signing string

The signature is computed over this exact string (note: the body is **hashed**, not
embedded raw — this binds the signature to the method, path, and body together):

```
method + "." + requestURI + "." + timestamp + "." + nonce + "." + base64(sha256(body))
```

- `method` — uppercase HTTP method, e.g. `POST`
- `requestURI` — the path only, e.g. `/api/payments` (no query string, no host)
- `timestamp` — the exact value sent in `X-Kimpay-Timestamp`
- `nonce` — the exact value sent in `X-Kimpay-Nonce`
- `base64(sha256(body))` — `Base64(SHA-256(rawRequestBodyBytes))`; for an empty body,
  hash the empty byte array

Sign that string with the merchant's **RSA private key** (`SHA256withRSA`) and Base64-encode
the result into `X-Kimpay-Signature`. KimPay verifies it against the merchant's stored
RSA **public key** (X.509, Base64, set on the `Merchant` record).

### Verification order (RequestSignatureFilter)

1. An authenticated `MerchantPrincipal` is present (else 401).
2. `Content-Length` ≤ `payment.security.max-body-bytes` (default 1 MiB) — oversized bodies are rejected before being read.
3. All three signing headers are present.
4. `timestamp` parses as a number and is within ±`payment.security.timestamp-tolerance-seconds` (default **300s**) of server time.
5. The RSA signature over the canonical string verifies.
6. The nonce is **fresh** — `registerNonce(keyId, nonce)` succeeds (atomic `SET key value NX` in Redis, key `payment:nonce:<keyId>:<nonce>`, TTL 600s).

The nonce is registered **only after** the signature passes, so a forged request can
never burn a victim's nonce. Any failure returns **401** with the envelope
`{"code":"SEC-001","message":"Request signature verification failed"}`.

---

## 3. Authorization (object-level)

Authentication proves *who* you are; authorization enforces that a merchant only acts on
**its own** resources. The `AuthorizationGuard` compares the authenticated
`MerchantPrincipal.merchantId` against the target resource's owner. A cross-merchant
access returns **404** (not 403) so resource existence is not leaked across tenants.

---

## 4. Error envelope

All errors return only:

```json
{ "code": "SEC-001", "message": "Human-readable summary" }
```

Codes come from the `ErrorCode` enum in `payment-common`. Stack traces, SQL, class
names, and other tenants' identifiers are never exposed
(`server.error.include-message: never`).

---

## 5. Worked example

```bash
# --- one-time: generate an RSA keypair; register the PUBLIC key on your merchant ---
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -outform DER | base64 -w0   # -> Merchant.publicKey

# --- per request ---
KEY_ID="pk_test_..."; SECRET="sk_test_..."
URI="/api/payments"
TS=$(date +%s)
NONCE=$(uuidgen)
BODY='{"userId":1,"merchantId":1,"walletId":1,"amount":"25.00","currency":"USD"}'

# body hash (Base64 of SHA-256 of the exact bytes you will send)
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -binary | base64)

# canonical string and signature
CANONICAL="POST.${URI}.${TS}.${NONCE}.${BODY_HASH}"
SIG=$(printf '%s' "$CANONICAL" \
  | openssl dgst -sha256 -sign private.pem \
  | openssl base64 -A)

curl -X POST "https://api.kimpay.example${URI}" \
  -H "Authorization: Bearer ${KEY_ID}:${SECRET}" \
  -H "X-Kimpay-Timestamp: ${TS}" \
  -H "X-Kimpay-Nonce: ${NONCE}" \
  -H "X-Kimpay-Signature: ${SIG}" \
  -H "Content-Type: application/json" \
  --data "$BODY"
```

> The bytes you hash for `BODY_HASH` must be **byte-for-byte identical** to the bytes you
> send. Serialize the JSON once and reuse it for both the hash and the request body.
