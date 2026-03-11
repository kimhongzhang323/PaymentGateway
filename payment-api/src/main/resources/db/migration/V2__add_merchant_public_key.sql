-- V2__add_merchant_public_key.sql
ALTER TABLE merchants ADD COLUMN public_key VARCHAR(2048);

-- Add a comment if supported (Postgres)
COMMENT ON COLUMN merchants.public_key IS 'Base64-encoded RSA public key for merchant request verification.';
