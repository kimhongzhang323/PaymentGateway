CREATE TABLE webhook_endpoints (
    id          BIGSERIAL    PRIMARY KEY,
    merchant_id BIGINT       NOT NULL REFERENCES merchants(id),
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(500) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

CREATE TABLE webhook_deliveries (
    id            BIGSERIAL    PRIMARY KEY,
    endpoint_id   BIGINT       NOT NULL REFERENCES webhook_endpoints(id),
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    last_response VARCHAR(1000),
    created_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_webhook_deliveries_pending
    ON webhook_deliveries(status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
