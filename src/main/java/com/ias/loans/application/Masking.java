package com.ias.loans.application;

/**
 * Para los logs: el id del cliente es un dato personal, asi que solo mostramos los ultimos 4 caracteres.
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
