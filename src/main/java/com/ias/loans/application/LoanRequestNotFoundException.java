package com.ias.loans.application;

public class LoanRequestNotFoundException extends RuntimeException {

    public LoanRequestNotFoundException(String field, String value) {
        super("No existe una solicitud con " + field + " '" + value + "'");
    }
}
