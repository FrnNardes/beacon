package com.beacon.protocol;

/**
 * Unrecoverable protocol-level error: malformed JSON, unknown type, etc.
 * Unchecked because the caller can't fix bad data from the wire.
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
