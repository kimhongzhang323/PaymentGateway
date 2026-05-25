# API Design

## REST conventions
- Base path `/api/<resource>` (e.g. `/api/payments`). Plural nouns for collections.
- Verbs map to HTTP methods: `POST` create, `GET` read, `POST /{id}/<action>` for lifecycle actions (`/capture`, `/void`, `/refund`) — actions are sub-resources, not query flags.
- Status codes: `201` on create, `200` on read/action, `400` invalid input, `401` unauthenticated, `403` unauthorized, `404` not found, `409` invalid state / duplicate, `429` rate limited, `5xx` only for genuine server faults.

## Request/response
- Request and response bodies are JSON, modeled as **records** in `payment-core/dto`.
- Amounts are decimal strings/numbers parsed to `BigDecimal`; currency is a 3-letter ISO code (uppercase, validated).
- Every mutating request supports an `idempotencyKey` and the signing headers (`X-Kimpay-Timestamp`, `X-Kimpay-Nonce`, `X-Kimpay-Signature`).

## Error envelope
All errors return:
```json
{ "code": "PAY-001", "message": "Human-readable summary" }
```
Codes come from the `ErrorCode` enum in `payment-common`. Add new codes there; never invent ad-hoc strings.

## Versioning & compatibility
- Breaking changes require a new version prefix (`/api/v2/...`) — never silently change a field's meaning.
- Additive changes (new optional fields) are allowed without a version bump.

## Webhooks (outbound, Phase 2+)
- Sign every webhook payload; include a timestamp and event id.
- Retry with exponential backoff; deliver at-least-once; consumers must be idempotent.

## Documentation
- Keep an OpenAPI spec in sync with controllers. Document auth, the signing scheme, and the error envelope in `docs/security/authentication.md`.
