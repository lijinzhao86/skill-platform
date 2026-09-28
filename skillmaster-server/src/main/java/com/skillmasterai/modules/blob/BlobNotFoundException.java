package com.skillmasterai.modules.blob;

/**
 * A digest that {@code version_file} references has no bytes behind it.
 *
 * <p>Unchecked, and not a client error: the only way to reach this is a database that has lost
 * bytes, which is an internal inconsistency rather than anything a caller did. The API layer
 * renders it as 500.
 */
public class BlobNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sha256Hex;

    public BlobNotFoundException(String sha256Hex) {
        super("no blob stored for sha256 " + sha256Hex);
        this.sha256Hex = sha256Hex;
    }

    public String sha256Hex() {
        return sha256Hex;
    }
}
