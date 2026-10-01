package com.ias.loans.infrastructure.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Maneja la tabla customer_daily_exposure: cuanto le hemos aprobado a cada cliente en cada dia.
 * Hay una fila por (cliente, dia). Uso SQL escrito a mano para que se vea exactamente que pasa.
 */
@Repository
public class DailyExposureRepository {

    private final DatabaseClient db;

    public DailyExposureRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * El UPDATE de abajo necesita que la fila del cliente para hoy ya exista, asi que aqui se crea con 0
     * si todavia no esta. Si dos solicitudes intentan crearla a la vez, una falla por llave duplicada
     * y ese error se ignora: lo importante es que la fila quede creada.
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
     * Esta es la clave de RF04 (solicitudes al mismo tiempo).
     *
     * En vez de "leer el total, comparar en Java y luego guardar" (3 pasos donde otra solicitud se puede
     * colar en medio), todo pasa en UN solo UPDATE: "sumale el monto, pero solo si no se pasa del limite".
     *
     * Mientras una solicitud hace este UPDATE, la BD bloquea la fila de ese cliente/dia hasta que termina
     * la transaccion. Si llega otra del mismo cliente, espera su turno y luego ve el total ya actualizado.
     *
     * @return true si habia cupo (se modifico 1 fila), false si no (0 filas).
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
