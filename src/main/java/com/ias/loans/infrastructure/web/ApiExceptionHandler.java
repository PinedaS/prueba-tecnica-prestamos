package com.ias.loans.infrastructure.web;

import com.ias.loans.application.IdempotencyConflictException;
import com.ias.loans.application.LoanRequestNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Convierte las excepciones en respuestas de error con el mismo formato siempre
 * (el estandar "problem+json": type, title, status, detail).
 * Nunca devolvemos la traza del error ni los valores que mando el cliente.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.example.com/problems/";

    @ExceptionHandler(WebExchangeBindException.class)
    ProblemDetail handleValidation(WebExchangeBindException ex) {
        List<Map<String, String>> errors = ex.getFieldErrors().stream()
                .sorted(Comparator.comparing(fe -> fe.getField()))
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        log.warn("event=loan_request_invalid fields={}", errors.stream().map(e -> e.get("field")).toList());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Solicitud invalida",
                "Uno o mas campos no cumplen las validaciones");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ServerWebInputException.class)
    ProblemDetail handleUnreadable(ServerWebInputException ex) {
        log.warn("event=request_unreadable reason={}", ex.getReason());
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Solicitud mal formada",
                "El cuerpo o los parametros de la peticion no tienen el formato esperado");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleConflict(IdempotencyConflictException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "request-reference-conflict",
                "Referencia ya procesada con datos diferentes", ex.getMessage());
        problem.setProperty("existingId", ex.existingId());
        return problem;
    }

    @ExceptionHandler(LoanRequestNotFoundException.class)
    ProblemDetail handleNotFound(LoanRequestNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Solicitud no encontrada", ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), String.valueOf(ex.getReason()));
        problem.setType(URI.create(TYPE_BASE + "http-" + ex.getStatusCode().value()));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("event=unexpected_error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno",
                "Ocurrio un error inesperado procesando la peticion");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
