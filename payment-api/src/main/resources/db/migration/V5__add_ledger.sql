-- Double-entry ledger: accounts, journal entries, and journal lines.

CREATE TABLE IF NOT EXISTS ledger_accounts (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(80)    NOT NULL,
    owner_type     VARCHAR(30)    NOT NULL,
    owner_id       BIGINT,
    classification VARCHAR(20)    NOT NULL,
    currency       CHAR(3)        NOT NULL,
    balance        NUMERIC(18,2)  NOT NULL DEFAULT 0.00,
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ledger_account_code_currency UNIQUE (code, currency)
);

CREATE INDEX IF NOT EXISTS idx_ledger_accounts_owner
    ON ledger_accounts (owner_type, owner_id);

CREATE TABLE IF NOT EXISTS journal_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT         NOT NULL REFERENCES transactions(id),
    event_type     VARCHAR(20)    NOT NULL,
    description    VARCHAR(255),
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_journal_entries_transaction_id
    ON journal_entries (transaction_id);

CREATE TABLE IF NOT EXISTS journal_lines (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL REFERENCES journal_entries(id),
    account_id       BIGINT         NOT NULL REFERENCES ledger_accounts(id),
    direction        VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount           NUMERIC(18,2)  NOT NULL CHECK (amount > 0),
    currency         CHAR(3)        NOT NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_journal_lines_entry
    ON journal_lines (journal_entry_id);
CREATE INDEX IF NOT EXISTS idx_journal_lines_account
    ON journal_lines (account_id);

-- Seed system accounts for USD.
INSERT INTO ledger_accounts (code, owner_type, classification, currency, balance)
VALUES
    ('SYS:PSP_CLEARING',  'SYSTEM', 'ASSET',   'USD', 0.00),
    ('SYS:FEE_REVENUE',   'SYSTEM', 'REVENUE',  'USD', 0.00),
    ('SYS:GATEWAY_CASH',  'SYSTEM', 'ASSET',   'USD', 0.00)
ON CONFLICT (code, currency) DO NOTHING;
