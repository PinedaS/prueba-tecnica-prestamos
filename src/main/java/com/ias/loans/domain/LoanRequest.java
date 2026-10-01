package com.ias.loans.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una solicitud ya procesada y guardada. Es un record (inmutable): una vez registrada nunca se modifica.
 */
public record LoanRequest(
        UUID id,
        LoanApplication application,
        LoanStatus status,
        RejectionReason rejectionReason,
        LocalDate businessDate,
        Instant processedAt,
        String payloadFingerprint) {
}
