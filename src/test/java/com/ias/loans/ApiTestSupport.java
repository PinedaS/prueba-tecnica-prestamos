package com.ias.loans;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base de pruebas de integracion: levanta la aplicacion completa (WebFlux + R2DBC + H2) en un puerto aleatorio
 * y la ejercita por HTTP. Cada prueba usa clientes y referencias unicos para no depender de otras pruebas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTestSupport {

    protected static final String BASE_PATH = "/api/v1/loan-requests";

    @Value("${local.server.port}")
    protected int port;

    protected WebTestClient client;

    @BeforeEach
    void setUpClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Solicitud valida que cumple las reglas individuales (score 720, monto <= 8x ingreso). */
    protected static Map<String, Object> validBody(String reference, String customerId, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestReference", reference);
        body.put("customerId", customerId);
        body.put("requestedAmount", amount);
        body.put("termMonths", 24);
        body.put("monthlyIncome", 5_000_000);
        body.put("creditScore", 720);
        return body;
    }
}
