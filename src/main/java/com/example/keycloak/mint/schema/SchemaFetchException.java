package com.example.keycloak.mint.schema;

public class SchemaFetchException extends RuntimeException {

    private final boolean transient_;

    public SchemaFetchException(String message, boolean isTransient) {
        super(message);
        this.transient_ = isTransient;
    }

    public SchemaFetchException(String message, Throwable cause, boolean isTransient) {
        super(message, cause);
        this.transient_ = isTransient;
    }

    public boolean isTransient() {
        return transient_;
    }
}
