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
 * Lo que llega en el body del POST. Las anotaciones (@Positive, @Min, @Max...) son las validaciones de RF02:
 * Spring las revisa solo, antes de entrar al metodo del controller, y si algo falla responde 400.
 */
public record LoanRequestBody(
        @NotBlank @Size(max = 100) String requestReference,
        @NotBlank @Size(max = 50) String customerId,
        @NotNull @Positive(message = "debe ser mayor que cero") @Digits(integer = 17, fraction = 2) BigDecimal requestedAmount,
        @NotNull @Min(value = 6, message = "debe estar entre 6 y 60 meses") @Max(value = 60, message = "debe estar entre 6 y 60 meses") Integer termMonths,
        @NotNull @Positive(message = "debe ser mayor que cero") @Digits(integer = 17, fraction = 2) BigDecimal monthlyIncome,
        @NotNull @Min(value = 0, message = "debe estar entre 0 y 1000") @Max(value = 1000, message = "debe estar entre 0 y 1000") Integer creditScore) {

    // Deja los montos siempre con 2 decimales (como en la BD), asi 1000 y 1000.00 se tratan igual
    LoanApplication toDomain() {
        return new LoanApplication(requestReference.trim(), customerId.trim(),
                requestedAmount.setScale(2, RoundingMode.UNNECESSARY), termMonths,
                monthlyIncome.setScale(2, RoundingMode.UNNECESSARY), creditScore);
    }
}
