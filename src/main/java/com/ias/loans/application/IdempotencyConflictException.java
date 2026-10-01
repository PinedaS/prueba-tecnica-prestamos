package com.ias.loans.application;

import java.util.UUID;

/**
 * La requestReference ya fue procesada con informacion diferente (RF05).
 */
public class IdempotencyConflictException extends RuntimeException {

    private final UUID existingId;

    public IdempotencyConflictException(String requestReference, UUID existingId) {
        super("La requestReference '" + requestReference + "' ya fue procesada con informacion diferente");
        this.existingId = existingId;
    }

    public UUID existingId() {
        return existingId;
    }
}
