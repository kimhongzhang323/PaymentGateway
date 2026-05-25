-- V3__add_api_credentials.sql
CREATE TABLE api_credentials (
    id            BIGSERIAL PRIMARY KEY,
    key_id        VARCHAR(64)  NOT NULL UNIQUE,
    secret_hash   VARCHAR(100) NOT NULL,
    merchant_id   BIGINT       NOT NULL REFERENCES merchants(id),
    status        VARCHAR(30)  NOT NULL DEFAULT 'active',
    last_used_at  TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_credentials_key_id ON api_credentials(key_id);
CREATE INDEX idx_api_credentials_merchant_id ON api_credentials(merchant_id);

COMMENT ON TABLE api_credentials IS 'Merchant API keys; secret stored only as a BCrypt hash.';
