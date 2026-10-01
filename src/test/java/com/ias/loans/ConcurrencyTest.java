package com.ias.loans;

import com.ias.loans.infrastructure.persistence.DailyExposureRepository;
import com.ias.loans.infrastructure.web.LoanRequestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RF04 y RF05 bajo concurrencia real: las peticiones se disparan en paralelo por HTTP contra la aplicacion
 * levantada, de modo que compiten de verdad por la misma fila de cupo diario / la misma referencia.
 */
class ConcurrencyTest extends ApiTestSupport {

    private static final BigDecimal DAILY_LIMIT = new BigDecimal("15000000");

    @Autowired
    private DailyExposureRepository dailyExposure;

    private WebClient webClient;

    @BeforeEach
    void setUpWebClient() {
        webClient = WebClient.create("http://localhost:" + port);
    }

    @RepeatedTest(5)
    void twoSimultaneousRequestsOfTenMillionApproveOnlyOne() {
        String customer = unique("CUST");

        List<ResponseEntity<LoanRequestResponse>> responses =
                fireConcurrently(2, i -> validBody(unique("REF"), customer, 10_000_000));

        assertThat(responses).allMatch(r -> r.getStatusCode().value() == 201);
        assertThat(countByStatus(responses, "APPROVED")).isEqualTo(1);
        assertThat(responses).filteredOn(r -> r.getBody().status().name().equals("REJECTED"))
                .singleElement()
                .satisfies(r -> assertThat(r.getBody().rejectionReason()).isEqualTo("DAILY_LIMIT_EXCEEDED"));
        assertThat(approvedTotalToday(customer)).isEqualByComparingTo("10000000");
    }

    @Test
    void manySimultaneousRequestsNeverExceedDailyLimit() {
        String customer = unique("CUST");

        // 40 solicitudes de 1.000.000 en paralelo: deben aprobarse exactamente 15
        List<ResponseEntity<LoanRequestResponse>> responses =
                fireConcurrently(40, i -> validBody(unique("REF"), customer, 1_000_000));

        assertThat(countByStatus(responses, "APPROVED")).isEqualTo(15);
        assertThat(countByStatus(responses, "REJECTED")).isEqualTo(25);
        BigDecimal approvedSum = responses.stream()
                .map(ResponseEntity::getBody)
                .filter(b -> b.status().name().equals("APPROVED"))
                .map(LoanRequestResponse::requestedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(approvedSum).isEqualByComparingTo(DAILY_LIMIT);
        assertThat(approvedTotalToday(customer)).isEqualByComparingTo(DAILY_LIMIT);
    }

    @RepeatedTest(5)
    void sameReferenceArrivingSimultaneouslyIsProcessedOnce() {
        String customer = unique("CUST");
        String reference = unique("REF");

        List<ResponseEntity<LoanRequestResponse>> responses =
                fireConcurrently(10, i -> validBody(reference, customer, 4_000_000));

        assertThat(responses).allMatch(r -> r.getStatusCode().is2xxSuccessful());
        assertThat(responses).filteredOn(r -> r.getStatusCode().value() == 201).hasSize(1);
        assertThat(responses).extracting(r -> r.getBody().id()).containsOnly(responses.get(0).getBody().id());
        assertThat(responses).allMatch(r -> r.getBody().status().name().equals("APPROVED"));
        // El cupo se consumio una sola vez
        assertThat(approvedTotalToday(customer)).isEqualByComparingTo("4000000");
    }

    private List<ResponseEntity<LoanRequestResponse>> fireConcurrently(int count, IntFunction<Map<String, Object>> bodyFactory) {
        List<Map<String, Object>> bodies = java.util.stream.IntStream.range(0, count).mapToObj(bodyFactory).toList();
        return Flux.fromIterable(bodies)
                .flatMap(body -> webClient.post().uri(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toEntity(LoanRequestResponse.class), count)
                .collectList()
                .block();
    }

    private static long countByStatus(List<ResponseEntity<LoanRequestResponse>> responses, String status) {
        return responses.stream().filter(r -> r.getBody().status().name().equals(status)).count();
    }

    private BigDecimal approvedTotalToday(String customer) {
        return dailyExposure.approvedTotal(customer, LocalDate.now(ZoneId.of("America/Bogota"))).block();
    }
}
