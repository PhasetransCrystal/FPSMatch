package com.ptcrys.fpsmatch.core.persistence;

public class DataPersistenceException extends RuntimeException {

    public DataPersistenceException(String message) {
        super(message);
    }

    public DataPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataPersistenceException(String message, String cause) {
        super(message, new RuntimeException(cause));
    }
}
