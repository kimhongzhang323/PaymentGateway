CREATE TABLE psp_webhook_events (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     VARCHAR(100) NOT NULL UNIQUE,
    event_type   VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    processed_at TIMESTAMP    NOT NULL
);
