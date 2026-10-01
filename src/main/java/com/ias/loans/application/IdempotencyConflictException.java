package com.ias.loans.application;

import java.util.UUID;

/**
 * Se lanza cuando llega una requestReference que ya existe pero con datos diferentes (RF05). Termina en un 409.
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
