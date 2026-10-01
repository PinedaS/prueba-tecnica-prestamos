package com.ias.loans.application;

import com.ias.loans.domain.LoanRequest;

/**
 * Lo que devuelve register(): la solicitud y si era una repeticion (replayed = true)
 * o una nueva. El controller usa replayed para decidir si responde 200 o 201.
 */
public record RegistrationResult(LoanRequest loanRequest, boolean replayed) {
}
