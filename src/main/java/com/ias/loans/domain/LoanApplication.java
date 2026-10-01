package com.ias.loans.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Datos de entrada de una solicitud, ya validados sintacticamente (RF02).
 */
public record LoanApplication(
        String requestReference,
        String customerId,
        BigDecimal requestedAmount,
        int termMonths,
        BigDecimal monthlyIncome,
        int creditScore) {

    /**
     * Huella SHA-256 del contenido de negocio. Permite saber si una requestReference repetida
     * trae exactamente la misma informacion (reintento) o informacion diferente (conflicto, RF05).
     * Los montos se normalizan para que 1000, 1000.0 y 1000.00 se consideren iguales.
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
