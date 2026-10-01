package com.ias.loans.infrastructure.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cupo aprobado por cliente y dia calendario (RF03/RF04).
 */
@Repository
public class DailyExposureRepository {

    private final DatabaseClient db;

    public DailyExposureRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * Crea la fila del cliente/dia con total 0 si no existe. Si dos solicitudes la crean a la vez,
     * la llave primaria garantiza que solo una gane; la otra ignora el error de duplicado.
     */
    public Mono<Void> ensureExists(String customerId, LocalDate businessDate) {
        return db.sql("""
                        INSERT INTO customer_daily_exposure (customer_id, business_date, approved_total)
                        SELECT :customerId, :businessDate, 0
                        WHERE NOT EXISTS (SELECT 1 FROM customer_daily_exposure
                                          WHERE customer_id = :customerId AND business_date = :businessDate)
                        """)
                .bind("customerId", customerId)
                .bind("businessDate", businessDate)
                .fetch().rowsUpdated()
                .onErrorResume(DataIntegrityViolationException.class, e -> Mono.just(0L))
                .then();
    }

    /**
     * Reserva cupo de forma atomica: el UPDATE solo afecta la fila si el nuevo total no supera el limite.
     * El motor bloquea la fila hasta el fin de la transaccion, por lo que solicitudes concurrentes del mismo
     * cliente se serializan y la segunda evalua la condicion sobre el total ya actualizado.
     *
     * @return true si se reservo el cupo.
     */
    public Mono<Boolean> tryReserve(String customerId, LocalDate businessDate, BigDecimal amount, BigDecimal limit) {
        return db.sql("""
                        UPDATE customer_daily_exposure
                           SET approved_total = approved_total + :amount
                         WHERE customer_id = :customerId
                           AND business_date = :businessDate
                           AND approved_total + :amount <= :limit
                        """)
                .bind("amount", amount)
                .bind("customerId", customerId)
                .bind("businessDate", businessDate)
                .bind("limit", limit)
                .fetch().rowsUpdated()
                .map(rows -> rows == 1);
    }

    public Mono<BigDecimal> approvedTotal(String customerId, LocalDate businessDate) {
        return db.sql("""
                        SELECT approved_total FROM customer_daily_exposure
                         WHERE customer_id = :customerId AND business_date = :businessDate
                        """)
                .bind("customerId", customerId)
                .bind("businessDate", businessDate)
                .map(row -> row.get("approved_total", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }
}
