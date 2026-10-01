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
 * Aqui esta el flujo principal: registrar y consultar solicitudes.
 *
 * Recordatorio rapido de WebFlux: los metodos devuelven Mono<T>, que es "un resultado que llega despues".
 * Se lee como una receta: "busca esto, si no existe haz aquello...". Spring la ejecuta cuando llega la peticion.
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

    /**
     * Registra una solicitud.
     *
     * 1. Si la requestReference ya existe, es una repeticion (RF05): se devuelve lo que ya habia.
     * 2. Si no existe, se evalua y se guarda como nueva.
     */
    public Mono<RegistrationResult> register(LoanApplication application) {
        String fingerprint = application.fingerprint();
        return loanRequests.findByReference(application.requestReference())
                .map(existing -> handleRepeated(existing, fingerprint))
                // Mono.defer: processNew solo se ejecuta si de verdad no se encontro la referencia
                .switchIfEmpty(Mono.defer(() -> processNew(application, fingerprint)))
                // El driver de H2 "bloquea" el hilo mientras espera a la base de datos. WebFlux tiene pocos hilos
                // y no se deben bloquear, asi que este trabajo se pasa a otro grupo de hilos (boundedElastic)
                // que si esta hecho para eso. Con PostgreSQL y su driver reactivo esta linea sobraria.
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

    /**
     * Procesa una solicitud que nunca habiamos visto.
     */
    private Mono<RegistrationResult> processNew(LoanApplication application, String fingerprint) {
        // Se corta a microsegundos porque es la precision con la que la BD guarda la fecha;
        // asi lo que respondemos ahora es exactamente igual a lo que se consulte despues.
        Instant processedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // El "dia" del limite diario es el dia en Colombia, no el del servidor
        LocalDate businessDate = LocalDate.ofInstant(processedAt, policy.businessZone());

        // Paso 1: reglas que solo dependen de esta solicitud (puntaje e ingreso)
        Optional<RejectionReason> failedRule = evaluator.evaluateIndividualRules(application);

        Mono<LoanRequest> saved;
        if (failedRule.isPresent()) {
            // Fallo una regla: se guarda rechazada y NO se toca el cupo diario del cliente
            saved = loanRequests.insert(newLoan(application, LoanStatus.REJECTED, failedRule.get(), businessDate, processedAt, fingerprint));
        } else {
            // Paso 2: paso las reglas individuales, ahora compite por el cupo diario (RF04)
            saved = dailyExposure.ensureExists(application.customerId(), businessDate)
                    .then(reserveQuotaAndSave(application, businessDate, processedAt, fingerprint));
        }

        return saved
                .doOnNext(this::logProcessed)
                .map(loan -> new RegistrationResult(loan, false))
                // Caso raro: la misma referencia llego dos veces al mismo tiempo. Las dos pasaron la busqueda
                // del inicio, pero la BD (columna UNIQUE) solo deja guardar una. La que pierde termina aqui
                // y responde con lo que guardo la ganadora.
                .onErrorResume(DataIntegrityViolationException.class, e -> loanRequests
                        .findByReference(application.requestReference())
                        .map(existing -> handleRepeated(existing, fingerprint))
                        .switchIfEmpty(Mono.error(e)));
    }

    /**
     * Intenta reservar cupo y guarda la solicitud, todo en UNA transaccion.
     * Si guardar la solicitud falla, la reserva de cupo se deshace sola (rollback).
     */
    private Mono<LoanRequest> reserveQuotaAndSave(LoanApplication application, LocalDate businessDate,
                                                  Instant processedAt, String fingerprint) {
        Mono<LoanRequest> reserveAndInsert = dailyExposure
                .tryReserve(application.customerId(), businessDate, application.requestedAmount(), policy.dailyApprovedLimit())
                .map(reserved -> reserved
                        ? newLoan(application, LoanStatus.APPROVED, null, businessDate, processedAt, fingerprint)
                        : newLoan(application, LoanStatus.REJECTED, RejectionReason.DAILY_LIMIT_EXCEEDED, businessDate, processedAt, fingerprint))
                .flatMap(loanRequests::insert);
        return transactional.transactional(reserveAndInsert);
    }

    /**
     * La referencia ya existia (RF05). Se compara la huella de los datos:
     * - igual: es un reintento del canal, devolvemos el resultado original;
     * - distinta: alguien mando otros datos con la misma referencia, respondemos error 409.
     */
    private RegistrationResult handleRepeated(LoanRequest existing, String fingerprint) {
        String reference = existing.application().requestReference();
        if (!existing.payloadFingerprint().equals(fingerprint)) {
            log.warn("event=loan_request_conflict requestReference={} existingId={} msg='misma referencia con datos diferentes, se deja la original'",
                    reference, existing.id());
            throw new IdempotencyConflictException(reference, existing.id());
        }
        log.info("event=loan_request_replayed requestReference={} id={} status={}", reference, existing.id(), existing.status());
        return new RegistrationResult(existing, true);
    }

    // Ojo: no se loguean montos, ingreso ni puntaje, y el cliente va enmascarado
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
