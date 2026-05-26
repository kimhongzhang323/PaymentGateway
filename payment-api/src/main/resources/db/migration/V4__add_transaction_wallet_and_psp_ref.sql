-- Persist enough context on a transaction to support deferred capture and PSP reconciliation.
ALTER TABLE transactions
    ADD COLUMN wallet_id     BIGINT,
    ADD COLUMN psp_reference VARCHAR(100);

-- Look up a transaction by its PSP reference when reconciling inbound PSP webhooks (Phase 2b).
CREATE INDEX IF NOT EXISTS idx_transactions_psp_reference ON transactions (psp_reference);
