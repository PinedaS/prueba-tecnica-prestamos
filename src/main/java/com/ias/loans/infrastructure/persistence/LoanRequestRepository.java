package com.ias.loans.infrastructure.persistence;

import com.ias.loans.domain.LoanApplication;
import com.ias.loans.domain.LoanRequest;
import com.ias.loans.domain.LoanStatus;
import com.ias.loans.domain.RejectionReason;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class LoanRequestRepository {

    private static final String SELECT = "SELECT * FROM loan_request ";

    private final DatabaseClient db;

    public LoanRequestRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * Guarda la solicitud. Si la requestReference ya existe, la BD lo impide (columna UNIQUE) y lanza
     * DataIntegrityViolationException. El servicio atrapa ese error y lo trata como una repeticion (RF05).
     */
    public Mono<LoanRequest> insert(LoanRequest loan) {
        LoanApplication app = loan.application();
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO loan_request (id, request_reference, customer_id, requested_amount, term_months,
                                                  monthly_income, credit_score, status, rejection_reason,
                                                  business_date, processed_at, payload_fingerprint)
                        VALUES (:id, :reference, :customerId, :amount, :term, :income, :score, :status, :reason,
                                :businessDate, :processedAt, :fingerprint)
                        """)
                .bind("id", loan.id())
                .bind("reference", app.requestReference())
                .bind("customerId", app.customerId())
                .bind("amount", app.requestedAmount())
                .bind("term", app.termMonths())
                .bind("income", app.monthlyIncome())
                .bind("score", app.creditScore())
                .bind("status", loan.status().name())
                .bind("businessDate", loan.businessDate())
                .bind("processedAt", loan.processedAt().atOffset(ZoneOffset.UTC))
                .bind("fingerprint", loan.payloadFingerprint());
        spec = loan.rejectionReason() == null
                ? spec.bindNull("reason", String.class)
                : spec.bind("reason", loan.rejectionReason().name());
        return spec.fetch().rowsUpdated().thenReturn(loan);
    }

    public Mono<LoanRequest> findById(UUID id) {
        return db.sql(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map(LoanRequestRepository::toDomain)
                .one();
    }

    public Mono<LoanRequest> findByReference(String requestReference) {
        return db.sql(SELECT + "WHERE request_reference = :reference")
                .bind("reference", requestReference)
                .map(LoanRequestRepository::toDomain)
                .one();
    }

    private static LoanRequest toDomain(Readable row) {
        LoanApplication app = new LoanApplication(
                row.get("request_reference", String.class),
                row.get("customer_id", String.class),
                row.get("requested_amount", BigDecimal.class),
                row.get("term_months", Integer.class),
                row.get("monthly_income", BigDecimal.class),
                row.get("credit_score", Integer.class));
        String reason = row.get("rejection_reason", String.class);
        return new LoanRequest(
                row.get("id", UUID.class),
                app,
                LoanStatus.valueOf(row.get("status", String.class)),
                reason == null ? null : RejectionReason.valueOf(reason),
                row.get("business_date", LocalDate.class),
                row.get("processed_at", OffsetDateTime.class).toInstant(),
                row.get("payload_fingerprint", String.class));
    }
}
