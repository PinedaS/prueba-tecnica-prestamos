package com.ias.loans.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Los datos que manda el canal en una solicitud (ya validados).
 */
public record LoanApplication(
        String requestReference,
        String customerId,
        BigDecimal requestedAmount,
        int termMonths,
        BigDecimal monthlyIncome,
        int creditScore) {

    /**
     * "Huella" de los datos: junta todos los campos en un texto y le saca un hash SHA-256.
     * Sirve para RF05: si llega una referencia repetida, comparamos huellas para saber si es
     * exactamente la misma solicitud (un reintento) o si trae datos distintos (conflicto).
     * stripTrailingZeros hace que 1000, 1000.0 y 1000.00 den la misma huella.
     */
    public String fingerprint() {
        String canonical = String.join("|",
                requestReference,
                customerId,
                normalize(requestedAmount),
                Integer.toString(termMonths),
                normalize(monthlyIncome),
                Integer.toString(creditScore));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
