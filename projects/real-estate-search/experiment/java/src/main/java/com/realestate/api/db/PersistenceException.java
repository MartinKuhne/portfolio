package com.realestate.api.db;

/**
 * Unchecked persistence exception thrown when a database schema or query operation fails.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
