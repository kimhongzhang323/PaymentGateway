package com.kimpay.payment.security;

public interface KeyProvider {
    /**
     * Returns the raw AES key bytes used for data encryption (DEK).
     * This can return a wrapped key that the provider unwraps internally.
     * Implementations MUST keep keys in memory only, avoid logging and persist securely.
     */
    byte[] getDataEncryptionKey() throws KeyProviderException;

    /**
     * Optionally support retrieving the key id or metadata for rotation.
     */
    default String getKeyId() { return "default"; }
}
