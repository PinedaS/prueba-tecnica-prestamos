package com.ias.loans.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Solicitud procesada y persistida. Es inmutable: una vez registrada no se modifica (RF05).
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
