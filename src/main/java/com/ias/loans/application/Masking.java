package com.ias.loans.application;

/**
 * Enmascaramiento para logs: el identificador del cliente es dato personal y no se registra completo.
 */
public final class Masking {

    private Masking() {
    }

    public static String customerId(String customerId) {
        if (customerId == null || customerId.length() <= 4) {
            return "****";
        }
        return "*".repeat(customerId.length() - 4) + customerId.substring(customerId.length() - 4);
    }
}
