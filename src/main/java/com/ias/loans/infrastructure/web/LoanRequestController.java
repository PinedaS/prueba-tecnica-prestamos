package com.ias.loans.infrastructure.web;

import com.ias.loans.application.LoanRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/loan-requests")
public class LoanRequestController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final LoanRequestService service;

    public LoanRequestController(LoanRequestService service) {
        this.service = service;
    }

    /**
     * Respuestas posibles:
     * - 201: solicitud nueva (puede quedar APPROVED o REJECTED, las dos son respuestas validas).
     * - 200 + header Idempotent-Replayed=true: ya la teniamos con los mismos datos, devolvemos la original.
     * - 409: ya la teniamos pero con datos diferentes.
     * - 400: datos invalidos (lo maneja ApiExceptionHandler).
     */
    @PostMapping
    public Mono<ResponseEntity<LoanRequestResponse>> register(@Valid @RequestBody LoanRequestBody body) {
        return service.register(body.toDomain()).map(result -> {
            LoanRequestResponse response = LoanRequestResponse.from(result.loanRequest());
            URI location = URI.create("/api/v1/loan-requests/" + response.id());
            return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                    .location(location)
                    .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                    .body(response);
        });
    }

    @GetMapping("/{id}")
    public Mono<LoanRequestResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(LoanRequestResponse::from);
    }

    @GetMapping(params = "requestReference")
    public Mono<LoanRequestResponse> findByReference(@RequestParam String requestReference) {
        return service.findByReference(requestReference).map(LoanRequestResponse::from);
    }
}
