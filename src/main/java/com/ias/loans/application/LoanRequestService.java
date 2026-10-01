package com.ias.loans.application;

import com.ias.loans.config.LoanPolicyProperties;
import com.ias.loans.domain.LoanApplication;
import com.ias.loans.domain.LoanEvaluator;
import com.ias.loans.domain.LoanRequest;
import com.ias.loans.domain.LoanStatus;
import com.ias.loans.domain.RejectionReason;
import com.ias.loans.infrastructure.persistence.DailyExposureRepository;
import com.ias.loans.infrastructure.persistence.LoanRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso de registro y consulta de solicitudes (RF01, RF03, RF04, RF05, RF06).
 */
@Service
public class LoanRequestService {

    private static final Logger log = LoggerFactory.getLogger(LoanRequestService.class);

    private final LoanRequestRepository loanRequests;
    private final DailyExposureRepository dailyExposure;
    private final LoanEvaluator evaluator;
    private final LoanPolicyProperties policy;
    private final TransactionalOperator transactional;
    private final Clock clock;

    public LoanRequestService(LoanRequestRepository loanRequests,
                              DailyExposureRepository dailyExposure,
                              LoanEvaluator evaluator,
                              LoanPolicyProperties policy,
                              ReactiveTransactionManager transactionManager,
                              Clock clock) {
        this.loanRequests = loanRequests;
        this.dailyExposure = dailyExposure;
        this.evaluator = evaluator;
        this.policy = policy;
        this.transactional = TransactionalOperator.create(transactionManager);
        this.clock = clock;
    }

    public Mono<RegistrationResult> register(LoanApplication application) {
        String fingerprint = application.fingerprint();
        return loanRequests.findByReference(application.requestReference())
                .map(existing -> replay(existing, fingerprint))
                .switchIfEmpty(Mono.defer(() -> processNew(application, fingerprint)))
                // r2dbc-h2 ejecuta las sentencias de forma bloqueante en el hilo que se suscribe; si dos
                // solicitudes compiten por el bloqueo de fila en el mismo hilo del event loop se bloquearian
                // mutuamente. Con un driver realmente reactivo (p. ej. r2dbc-postgresql) esto no es necesario.
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<LoanRequest> findById(UUID id) {
        return loanRequests.findById(id)
                .switchIfEmpty(Mono.error(() -> new LoanRequestNotFoundException("id", id.toString())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<LoanRequest> findByReference(String requestReference) {
        return loanRequests.findByReference(requestReference)
                .switchIfEmpty(Mono.error(() -> new LoanRequestNotFoundException("requestReference", requestReference)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<RegistrationResult> processNew(LoanApplication application, String fingerprint) {
        Instant processedAt = clock.instant().truncatedTo(ChronoUnit.MICROS); // precision de TIMESTAMP en BD
        LocalDate businessDate = LocalDate.ofInstant(processedAt, policy.businessZone());
        Optional<RejectionReason> individualRejection = evaluator.evaluateIndividualRules(application);

        Mono<LoanRequest> decision = individualRejection
                .map(reason -> Mono.just(newLoan(application, LoanStatus.REJECTED, reason, businessDate, processedAt, fingerprint)))
                .orElseGet(() -> dailyExposure
                        .tryReserve(application.customerId(), businessDate, application.requestedAmount(), policy.dailyApprovedLimit())
                        .map(reserved -> reserved
                                ? newLoan(application, LoanStatus.APPROVED, null, businessDate, processedAt, fingerprint)
                                : newLoan(application, LoanStatus.REJECTED, RejectionReason.DAILY_LIMIT_EXCEEDED, businessDate, processedAt, fingerprint)));

        Mono<Void> prepareExposure = individualRejection.isPresent()
                ? Mono.empty()
                : dailyExposure.ensureExists(application.customerId(), businessDate);

        // Reserva de cupo + insercion de la solicitud en una sola transaccion: si la insercion falla
        // (p. ej. la misma referencia llego en paralelo), la reserva se revierte.
        return prepareExposure
                .then(decision.flatMap(loanRequests::insert).as(transactional::transactional))
                .doOnNext(this::logProcessed)
                .map(loan -> new RegistrationResult(loan, false))
                .onErrorResume(DataIntegrityViolationException.class, e -> loanRequests
                        .findByReference(application.requestReference())
                        .map(existing -> replay(existing, fingerprint))
                        .switchIfEmpty(Mono.error(e)));
    }

    private RegistrationResult replay(LoanRequest existing, String fingerprint) {
        String reference = existing.application().requestReference();
        if (!existing.payloadFingerprint().equals(fingerprint)) {
            log.warn("event=loan_request_conflict requestReference={} existingId={} msg='misma referencia con datos diferentes; se conserva la original'",
                    reference, existing.id());
            throw new IdempotencyConflictException(reference, existing.id());
        }
        log.info("event=loan_request_replayed requestReference={} id={} status={}", reference, existing.id(), existing.status());
        return new RegistrationResult(existing, true);
    }

    private void logProcessed(LoanRequest loan) {
        log.info("event=loan_request_processed id={} requestReference={} customer={} status={} reason={}",
                loan.id(), loan.application().requestReference(), Masking.customerId(loan.application().customerId()),
                loan.status(), loan.rejectionReason());
    }

    private static LoanRequest newLoan(LoanApplication application, LoanStatus status, RejectionReason reason,
                                       LocalDate businessDate, Instant processedAt, String fingerprint) {
        return new LoanRequest(UUID.randomUUID(), application, status, reason, businessDate, processedAt, fingerprint);
    }
}
