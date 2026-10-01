package com.ias.loans.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reglas de evaluacion que dependen solo de la solicitud (RF03).
 * La regla del limite diario depende del estado compartido del cliente y se resuelve
 * de forma atomica en la persistencia (ver DailyExposureRepository).
 */
public class LoanEvaluator {

    private final int minCreditScore;
    private final BigDecimal maxIncomeMultiplier;

    public LoanEvaluator(int minCreditScore, BigDecimal maxIncomeMultiplier) {
        this.minCreditScore = minCreditScore;
        this.maxIncomeMultiplier = maxIncomeMultiplier;
    }

    /**
     * @return la primera regla individual incumplida, o vacio si la solicitud puede competir por cupo diario.
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
