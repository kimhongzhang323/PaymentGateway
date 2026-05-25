# Glossary

Domain and project terms used across KimPay.

## Payment domain
- **Authorization** — reserving funds without moving them; transaction status `AUTHORIZED`.
- **Capture** — completing a payment so funds move; status `CAPTURED`. May be immediate (auto-capture) or deferred.
- **Void** — cancelling an authorization before capture; status `VOIDED`.
- **Refund** — returning captured funds. **Partial refund** returns part; cumulative refunds tracked, status `PARTIALLY_REFUNDED` until fully refunded (`REFUNDED`).
- **Settlement** — the batch movement of captured funds from acquirer to merchant (Phase 2).
- **Reconciliation** — comparing gateway records against PSP records to detect drift (Phase 2).
- **Chargeback** — a customer-initiated reversal via the card network.
- **Idempotency key** — client-supplied key making a create request safe to retry exactly once.

## Architecture / infra
- **PSP / Acquirer** — Payment Service Provider / acquiring bank. Abstracted behind `PspConnector` (Stripe test first).
- **Ledger** — double-entry record where every movement has balancing debit/credit (Phase 2).
- **Wallet** — a user's stored balance; debited under lock for wallet payments.
- **Idempotency lock / wallet lock** — Redisson distributed locks (`payment:lock:...`) serializing concurrent operations across nodes.
- **Nonce** — single-use value per signed request; replay-checked in Redis.
- **Distributed lock + pessimistic lock** — the two-layer concurrency defense (app layer + DB `SELECT ... FOR UPDATE`).

## Security
- **MerchantPrincipal** — authenticated identity (`merchantId`, `keyId`) set by the API-key filter.
- **API key pair** — public `pk_test_*` identifier + secret `sk_test_*` (stored only as a BCrypt hash).
- **Request signing** — RSA signature over `timestamp + "." + nonce + "." + body`, verified with the merchant's stored public key.
- **SAQ-A posture** — minimal PCI scope achieved by never handling/storing raw PANs (tokenize via PSP).
- **Error envelope** — standard `{ "code", "message" }` response; codes from the `ErrorCode` enum.
