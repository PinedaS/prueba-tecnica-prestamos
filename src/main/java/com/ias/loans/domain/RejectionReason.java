package com.ias.loans.domain;

/**
 * Razon general de rechazo (RF03). Se expone el codigo y una descripcion legible,
 * sin revelar umbrales internos ni datos de otras solicitudes del cliente.
 */
public enum RejectionReason {
    CREDIT_SCORE_TOO_LOW("El puntaje de credito no alcanza el minimo requerido"),
    AMOUNT_EXCEEDS_INCOME_CAPACITY("El monto solicitado excede la capacidad segun el ingreso mensual declarado"),
    DAILY_LIMIT_EXCEEDED("El total aprobado del cliente en el dia superaria el limite permitido");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
