package com.ias.loans.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Las dos reglas de RF03 que se pueden revisar mirando solo la solicitud: puntaje e ingreso.
 * La tercera (limite diario) depende de las otras solicitudes del cliente, por eso no esta aqui:
 * se resuelve en la base de datos (ver DailyExposureRepository.tryReserve).
 *
 * Es Java puro, sin Spring, asi que se prueba facil con pruebas unitarias (LoanEvaluatorTest).
 */
public class LoanEvaluator {

    private final int minCreditScore;
    private final BigDecimal maxIncomeMultiplier;

    public LoanEvaluator(int minCreditScore, BigDecimal maxIncomeMultiplier) {
        this.minCreditScore = minCreditScore;
        this.maxIncomeMultiplier = maxIncomeMultiplier;
    }

    /**
     * Devuelve la primera regla que no se cumple, o vacio si paso las dos.
     * Los limites son inclusivos: 650 pasa, y pedir exactamente 8 veces el ingreso tambien pasa.
     */
    public Optional<RejectionReason> evaluateIndividualRules(LoanApplication application) {
        if (application.creditScore() < minCreditScore) {
            return Optional.of(RejectionReason.CREDIT_SCORE_TOO_LOW);
        }
        BigDecimal maxAmount = application.monthlyIncome().multiply(maxIncomeMultiplier);
        if (application.requestedAmount().compareTo(maxAmount) > 0) {
            return Optional.of(RejectionReason.AMOUNT_EXCEEDS_INCOME_CAPACITY);
        }
        return Optional.empty();
    }
}
