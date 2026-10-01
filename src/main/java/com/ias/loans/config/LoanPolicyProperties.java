package com.ias.loans.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.ZoneId;

@Validated
@ConfigurationProperties(prefix = "loans")
public record LoanPolicyProperties(
        @NotNull Integer minCreditScore,
        @NotNull @Positive BigDecimal maxIncomeMultiplier,
        @NotNull @Positive BigDecimal dailyApprovedLimit,
        @NotNull ZoneId businessZone) {
}
