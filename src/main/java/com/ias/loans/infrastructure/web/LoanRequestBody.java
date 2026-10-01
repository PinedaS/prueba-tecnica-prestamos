package com.ias.loans.infrastructure.web;

import com.ias.loans.domain.LoanApplication;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Contrato de entrada de POST /api/v1/loan-requests. Validaciones RF02 + validaciones de formato.
 */
public record LoanRequestBody(
        @NotBlank @Size(max = 100) String requestReference,
        @NotBlank @Size(max = 50) String customerId,
        @NotNull @Positive(message = "debe ser mayor que cero") @Digits(integer = 17, fraction = 2) BigDecimal requestedAmount,
        @NotNull @Min(value = 6, message = "debe estar entre 6 y 60 meses") @Max(value = 60, message = "debe estar entre 6 y 60 meses") Integer termMonths,
        @NotNull @Positive(message = "debe ser mayor que cero") @Digits(integer = 17, fraction = 2) BigDecimal monthlyIncome,
        @NotNull @Min(value = 0, message = "debe estar entre 0 y 1000") @Max(value = 1000, message = "debe estar entre 0 y 1000") Integer creditScore) {

    /** Normaliza los montos a 2 decimales (escala de persistencia); @Digits garantiza que no hay redondeo. */
    LoanApplication toDomain() {
        return new LoanApplication(requestReference.trim(), customerId.trim(),
                requestedAmount.setScale(2, RoundingMode.UNNECESSARY), termMonths,
                monthlyIncome.setScale(2, RoundingMode.UNNECESSARY), creditScore);
    }
}
