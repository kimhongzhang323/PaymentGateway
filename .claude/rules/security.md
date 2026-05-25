# Security Rules

This is a payment gateway. Security is the credibility bar — treat every rule here as mandatory.

## Cardholder data & PII
- **Never store PANs.** Tokenize via the PSP and store only the token. Sandbox-grade still follows SAQ-A posture.
- Sensitive fields at rest (bank accounts, routing numbers, QR payloads) use the `EncryptedStringConverter` (AES-256-GCM). Never persist them in plaintext.
- **Never log** PANs, CVV, secret keys, private keys, full tokens, or decrypted PII. Route any risky string through `SensitiveDataMasker`; logback also masks at the appender as defense-in-depth.

## Authentication & request integrity
- Every endpoint requires authentication except explicit public paths (`/actuator/health`, `/actuator/info`). Default to `authenticated()`.
- Merchant secret API keys are stored only as a **BCrypt hash**. The plaintext secret is shown once at issuance and never retrievable.
- Mutating requests must carry a timestamp (±300s window), a unique nonce (replay-checked in Redis), and an RSA signature verified against the merchant's stored public key.
- Sessions are **stateless**; no server-side session, no CSRF cookie flow (token/HMAC auth instead).

## Secrets & keys
- No secrets in source, in `application.yml` defaults, or in logs. Inject via environment / KMS.
- The encryption key provider is selectable (`payment.encryption.key-provider`): `env` for local, `kms` for deployed. Prefer KMS in any shared environment.
- Support key rotation via versioned ciphertext; never hard-code key material.

## Error & input handling
- Validate all DTOs at the boundary (Jakarta Bean Validation). Reject early with HTTP 400.
- Error responses return only `{ code, message }`. Never leak stack traces, SQL, class names, or internal IDs of other tenants.
- Enforce idempotency on payment creation; guard against duplicate processing under concurrency.

## Dependencies & supply chain
- No new dependency without justification. Run dependency/vulnerability scanning in CI; block high/critical CVEs.

## Authorization
- A merchant may only act on its own resources. Check the authenticated `MerchantPrincipal.merchantId` against the target resource's owner before mutating or returning data.
