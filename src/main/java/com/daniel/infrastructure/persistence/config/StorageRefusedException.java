package com.daniel.infrastructure.persistence.config;

/**
 * A write refused by the storage gate before any SQL ran (BRK-31): the message says why and what to do, and nothing
 * was written.
 */
public final class StorageRefusedException extends RuntimeException {

    public StorageRefusedException(String message) {
        super(message);
    }
}
