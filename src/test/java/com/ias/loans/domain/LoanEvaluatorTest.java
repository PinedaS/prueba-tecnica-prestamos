package com.ias.loans.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LoanEvaluatorTest {

    private final LoanEvaluator evaluator = new LoanEvaluator(650, new BigDecimal("8"));

    @Test
    void passesWhenScoreAndIncomeRatioAreAtTheBoundary() {
        // 650 es el minimo y 8.000.000 = 1.000.000 * 8 es el maximo permitido (ambos inclusivos)
        assertThat(evaluator.evaluateIndividualRules(app("8000000", "1000000", 650))).isEmpty();
    }

    @Test
    void rejectsWhenScoreIsBelowMinimum() {
        assertThat(evaluator.evaluateIndividualRules(app("1000000", "1000000", 649)))
                .contains(RejectionReason.CREDIT_SCORE_TOO_LOW);
    }

    @Test
    void rejectsWhenAmountExceedsEightTimesIncome() {
        assertThat(evaluator.evaluateIndividualRules(app("8000000.01", "1000000", 800)))
                .contains(RejectionReason.AMOUNT_EXCEEDS_INCOME_CAPACITY);
    }

    @Test
    void reportsScoreFirstWhenSeveralRulesFail() {
        assertThat(evaluator.evaluateIndividualRules(app("99000000", "1000000", 100)))
                .contains(RejectionReason.CREDIT_SCORE_TOO_LOW);
    }

    @Test
    void fingerprintIgnoresAmountScaleButDetectsDifferentData() {
        LoanApplication a = app("1000000", "500000", 700);
        LoanApplication sameWithOtherScale = app("1000000.00", "500000.0", 700);
        LoanApplication different = app("1000001", "500000", 700);

        assertThat(a.fingerprint()).isEqualTo(sameWithOtherScale.fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(different.fingerprint());
    }

    private static LoanApplication app(String amount, String income, int score) {
        return new LoanApplication("REF-1", "CUST-1", new BigDecimal(amount), 12, new BigDecimal(income), score);
    }
}
