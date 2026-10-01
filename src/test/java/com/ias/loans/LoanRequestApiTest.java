package com.ias.loans;

import com.ias.loans.infrastructure.web.LoanRequestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LoanRequestApiTest extends ApiTestSupport {

    @Test
    void approvesValidRequestAndAllowsQueryingItByIdAndReference() {
        String reference = unique("REF");
        LoanRequestResponse created = register(validBody(reference, unique("CUST"), 5_000_000))
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "false")
                .expectHeader().exists("Location")
                .expectBody(LoanRequestResponse.class).returnResult().getResponseBody();

        assertThat(created.status().name()).isEqualTo("APPROVED");
        assertThat(created.rejectionReason()).isNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.processedAt()).isNotNull();

        client.get().uri(BASE_PATH + "/{id}", created.id()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestReference").isEqualTo(reference)
                .jsonPath("$.status").isEqualTo("APPROVED");

        client.get().uri(b -> b.path(BASE_PATH).queryParam("requestReference", reference).build()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(created.id().toString());
    }

    @Test
    void rejectsWhenCreditScoreIsTooLow() {
        Map<String, Object> body = validBody(unique("REF"), unique("CUST"), 1_000_000);
        body.put("creditScore", 649);

        register(body).expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REJECTED")
                .jsonPath("$.rejectionReason").isEqualTo("CREDIT_SCORE_TOO_LOW")
                .jsonPath("$.rejectionDetail").isNotEmpty();
    }

    @Test
    void rejectsWhenAmountExceedsEightTimesMonthlyIncome() {
        Map<String, Object> body = validBody(unique("REF"), unique("CUST"), 8_000_001);
        body.put("monthlyIncome", 1_000_000);

        register(body).expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REJECTED")
                .jsonPath("$.rejectionReason").isEqualTo("AMOUNT_EXCEEDS_INCOME_CAPACITY");
    }

    @Test
    void rejectsWhenDailyApprovedTotalWouldExceedLimit() {
        String customer = unique("CUST");

        register(validBody(unique("REF"), customer, 10_000_000)).expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");
        register(validBody(unique("REF"), customer, 6_000_000)).expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REJECTED")
                .jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");
        // Un rechazo no consume cupo: todavia caben exactamente 5.000.000 (limite inclusivo)
        register(validBody(unique("REF"), customer, 5_000_000)).expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");
        register(validBody(unique("REF"), customer, 1)).expectStatus().isCreated()
                .expectBody().jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void dailyLimitIsPerCustomer() {
        register(validBody(unique("REF"), unique("CUST"), 15_000_000)).expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");
        register(validBody(unique("REF"), unique("CUST"), 15_000_000)).expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("requestedAmount", 0),
                Arguments.of("requestedAmount", -100),
                Arguments.of("termMonths", 5),
                Arguments.of("termMonths", 61),
                Arguments.of("monthlyIncome", 0),
                Arguments.of("creditScore", -1),
                Arguments.of("creditScore", 1001),
                Arguments.of("requestReference", " "),
                Arguments.of("customerId", null));
    }

    @ParameterizedTest(name = "{0}={1} -> 400")
    @MethodSource("invalidInputs")
    void rejectsInvalidInputWithControlledError(String field, Object value) {
        String reference = unique("REF");
        Map<String, Object> body = validBody(reference, unique("CUST"), 1_000_000);
        body.put(field, value);

        register(body).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Solicitud invalida")
                .jsonPath("$.errors[0].field").isEqualTo(field);

        // Una entrada invalida no se registra
        client.get().uri(b -> b.path(BASE_PATH).queryParam("requestReference", reference).build()).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void rejectsMalformedJson() {
        client.post().uri(BASE_PATH).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requestReference\": \"X\", \"requestedAmount\": \"mucho\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.title").isEqualTo("Solicitud mal formada");
    }

    @Test
    void returnsNotFoundForUnknownRequest() {
        client.get().uri(BASE_PATH + "/{id}", "00000000-0000-0000-0000-000000000000").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.title").isEqualTo("Solicitud no encontrada");
    }

    @Test
    void repeatedReferenceWithSameDataReturnsOriginalResultWithoutConsumingQuota() {
        String customer = unique("CUST");
        Map<String, Object> body = validBody(unique("REF"), customer, 10_000_000);

        LoanRequestResponse first = register(body).expectStatus().isCreated()
                .expectBody(LoanRequestResponse.class).returnResult().getResponseBody();
        LoanRequestResponse second = register(body).expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody(LoanRequestResponse.class).returnResult().getResponseBody();

        assertThat(second).isEqualTo(first);
        // Si el reintento hubiera sumado otra vez, el cliente tendria 20.000.000 y esto se rechazaria
        register(validBody(unique("REF"), customer, 5_000_000)).expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");
    }

    @Test
    void repeatedReferenceKeepsFirstResultEvenIfItWasRejected() {
        String reference = unique("REF");
        Map<String, Object> body = validBody(reference, unique("CUST"), 1_000_000);
        body.put("creditScore", 500);

        register(body).expectStatus().isCreated().expectBody().jsonPath("$.status").isEqualTo("REJECTED");
        register(body).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("REJECTED");
    }

    @Test
    void repeatedReferenceWithDifferentDataIsConflictAndOriginalIsUnchanged() {
        String reference = unique("REF");
        String customer = unique("CUST");
        LoanRequestResponse original = register(validBody(reference, customer, 2_000_000)).expectStatus().isCreated()
                .expectBody(LoanRequestResponse.class).returnResult().getResponseBody();

        register(validBody(reference, customer, 9_000_000)).expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.existingId").isEqualTo(original.id().toString());

        LoanRequestResponse stored = client.get().uri(BASE_PATH + "/{id}", original.id()).exchange()
                .expectStatus().isOk()
                .expectBody(LoanRequestResponse.class).returnResult().getResponseBody();
        assertThat(stored).isEqualTo(original);
    }

    private WebTestClient.ResponseSpec register(Map<String, Object> body) {
        return client.post().uri(BASE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }
}
