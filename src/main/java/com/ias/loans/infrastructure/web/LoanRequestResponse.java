package com.ias.loans.infrastructure.web;

import com.ias.loans.domain.LoanApplication;
import com.ias.loans.domain.LoanRequest;
import com.ias.loans.domain.LoanStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanRequestResponse(
        UUID id,
        String requestReference,
        String customerId,
        BigDecimal requestedAmount,
        int termMonths,
        BigDecimal monthlyIncome,
        int creditScore,
        LoanStatus status,
        String rejectionReason,
        String rejectionDetail,
        LocalDate businessDate,
        Instant processedAt) {

    static LoanRequestResponse from(LoanRequest loan) {
        LoanApplication app = loan.application();
        return new LoanRequestResponse(
                loan.id(),
                app.requestReference(),
                app.customerId(),
                app.requestedAmount(),
                app.termMonths(),
                app.monthlyIncome(),
                app.creditScore(),
                loan.status(),
                loan.rejectionReason() == null ? null : loan.rejectionReason().name(),
                loan.rejectionReason() == null ? null : loan.rejectionReason().description(),
                loan.businessDate(),
                loan.processedAt());
    }
}
