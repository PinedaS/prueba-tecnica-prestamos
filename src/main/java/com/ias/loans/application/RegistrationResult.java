package com.ias.loans.application;

import com.ias.loans.domain.LoanRequest;

/**
 * @param replayed true cuando la requestReference ya habia sido procesada y se devuelve el resultado original.
 */
public record RegistrationResult(LoanRequest loanRequest, boolean replayed) {
}
