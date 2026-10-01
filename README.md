# Servicio de solicitudes de préstamo personal

Backend en **Java 17 + Spring Boot 4 + Spring WebFlux + R2DBC** para registrar y consultar solicitudes de préstamo,
garantizando que:

- una `requestReference` repetida **no crea una segunda solicitud ni altera el cupo** (RF05), y
- el total aprobado por cliente y día **nunca supera 15.000.000 COP aunque lleguen solicitudes simultáneas** (RF04).

---

## 1. Cómo ejecutar

**Requisito único:** JDK 17 o superior (`JAVA_HOME` configurado). Maven no es necesario: se usa el wrapper incluido.
No se requiere Docker ni base de datos externa (H2 en memoria).

```bash
# Linux / macOS / Git Bash
./mvnw spring-boot:run

# Windows (cmd / PowerShell)
mvnw.cmd spring-boot:run
```

La API queda en `http://localhost:8080`. Salud: `GET /actuator/health`.
El archivo [`requests.http`](requests.http) trae ejemplos listos (IntelliJ / VS Code REST Client), o con curl:

```bash
curl -i -X POST http://localhost:8080/api/v1/loan-requests \
  -H "Content-Type: application/json" \
  -d '{"requestReference":"CANAL-APP-0001","customerId":"CLI-0001","requestedAmount":10000000,"termMonths":36,"monthlyIncome":4000000,"creditScore":720}'
```

### Pruebas

```bash
./mvnw test        # Windows: mvnw.cmd test
```

35 pruebas (unitarias + integración HTTP contra la aplicación levantada en puerto aleatorio), ~20 s.

---

## 2. Contrato HTTP

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/api/v1/loan-requests` | **201** solicitud nueva procesada (aprobada **o** rechazada por regla de negocio). **200** + header `Idempotent-Replayed: true` si la referencia ya existía con los mismos datos (se devuelve el resultado original). **409** si la referencia ya existía con datos diferentes. **400** entrada inválida. |
| `GET` | `/api/v1/loan-requests/{id}` | **200** solicitud, **404** si no existe, **400** si el id no es UUID. |
| `GET` | `/api/v1/loan-requests?requestReference=...` | **200** solicitud, **404** si no existe. |

**Cuerpo del POST**

```json
{
  "requestReference": "CANAL-APP-0001",
  "customerId": "CLI-0001",
  "requestedAmount": 10000000,
  "termMonths": 36,
  "monthlyIncome": 4000000,
  "creditScore": 720
}
```

**Respuesta (POST y GET)**

```json
{
  "id": "d2d21333-86ca-4990-925c-c7b49104193f",
  "requestReference": "CANAL-APP-0001",
  "customerId": "CLI-0001",
  "requestedAmount": 10000000.00,
  "termMonths": 36,
  "monthlyIncome": 4000000.00,
  "creditScore": 720,
  "status": "APPROVED",
  "rejectionReason": null,
  "rejectionDetail": null,
  "businessDate": "2026-10-01",
  "processedAt": "2026-10-01T15:57:59.280688Z"
}
```

`rejectionReason` ∈ `CREDIT_SCORE_TOO_LOW`, `AMOUNT_EXCEEDS_INCOME_CAPACITY`, `DAILY_LIMIT_EXCEEDED`, con `rejectionDetail` legible.

**Errores**: formato uniforme `application/problem+json` (RFC 9457). En validaciones se listan todos los campos:

```json
{
  "type": "https://api.example.com/problems/validation-error",
  "title": "Solicitud invalida",
  "status": 400,
  "detail": "Uno o mas campos no cumplen las validaciones",
  "instance": "/api/v1/loan-requests",
  "errors": [
    { "field": "creditScore", "message": "debe estar entre 0 y 1000" },
    { "field": "termMonths",  "message": "debe estar entre 6 y 60 meses" }
  ]
}
```

---

## 3. Estructura

```
src/main/java/com/ias/loans
├── domain/              Modelo y reglas puras (LoanApplication, LoanEvaluator, RejectionReason...)
├── application/         Caso de uso: LoanRequestService (orquesta evaluación, cupo, idempotencia, transacción)
├── infrastructure/
│   ├── persistence/     SQL explícito con DatabaseClient (LoanRequestRepository, DailyExposureRepository)
│   └── web/             Controller, DTOs con Bean Validation, manejo de errores
└── config/              Propiedades de negocio (application.properties → loans.*) y Clock
src/main/resources/schema.sql   Tablas loan_request y customer_daily_exposure
```

Flujo de `POST`:

1. Bean Validation (RF02) → 400 si falla; **no se persiste**.
2. Se busca la `requestReference`. Si existe: misma huella → 200 con el resultado original; huella distinta → 409.
3. Reglas individuales (score ≥ 650, monto ≤ 8 × ingreso). Si fallan → se guarda `REJECTED` con su razón.
4. Si pasan: en **una transacción** se reserva cupo con un `UPDATE` condicional atómico y se inserta la solicitud
   (`APPROVED`, o `REJECTED / DAILY_LIMIT_EXCEEDED` si no hubo cupo).
5. Si el `INSERT` choca con la restricción única de `request_reference` (misma referencia llegando en paralelo),
   la transacción se revierte —incluida la reserva de cupo— y se responde como en el paso 2.

---

## 4. Decisiones técnicas

### D1. Control de concurrencia del límite diario (RF04)

- **Problema:** dos solicitudes del mismo cliente procesadas a la vez pueden leer "total = 0" y aprobarse ambas
  (condición de carrera *read-check-write*).
- **Alternativas evaluadas:**
  1. Lock en memoria por cliente (`ConcurrentHashMap` / colas por cliente): simple, pero solo funciona con una
     instancia y se pierde si la app se reinicia.
  2. `SELECT ... FOR UPDATE` sobre una fila de cupo y luego decidir en Java: correcto, pero más pasos y más código
     transaccional.
  3. Bloqueo optimista con versión + reintentos: correcto, pero bajo alta contención genera reintentos y
     complejidad extra.
  4. **`UPDATE` condicional atómico** sobre una fila `customer_daily_exposure(customer_id, business_date)`.
- **Decisión:** opción 4.
  ```sql
  UPDATE customer_daily_exposure
     SET approved_total = approved_total + :amount
   WHERE customer_id = :customerId AND business_date = :businessDate
     AND approved_total + :amount <= :limit
  ```
  Si afecta 1 fila → aprobada; 0 filas → rechazada por `DAILY_LIMIT_EXCEEDED`. El motor bloquea la fila hasta el
  commit, así que las solicitudes concurrentes del mismo cliente se serializan **solo entre ellas** (clientes
  distintos no se bloquean) y la segunda evalúa la condición sobre el total ya actualizado.
- **Ventaja:** la invariante la garantiza la base de datos, no la memoria del proceso → sirve con varias instancias.
  **Trade-off:** se mantiene un acumulado desnormalizado que debe estar en la misma transacción que la solicitud
  (lo está), y hay espera por bloqueo bajo alta contención de un mismo cliente.
- **Verificación:** `ConcurrencyTest` dispara peticiones HTTP **en paralelo** contra la app levantada:
  2 × 10.000.000 simultáneas → exactamente 1 aprobada (repetida 5 veces); 40 × 1.000.000 simultáneas → exactamente 15
  aprobadas y total = 15.000.000. **Prueba de mutación manual:** reemplacé temporalmente el `UPDATE` atómico por
  "leer total → comparar en Java → actualizar"; 6 de las 11 pruebas de concurrencia fallaron (se aprobaban
  20.000.000), lo que confirma que las pruebas detectan la carrera.
- **A otra escala:** PostgreSQL con el mismo `UPDATE` (el comportamiento de re-evaluar el `WHERE` tras el bloqueo
  está garantizado en READ COMMITTED). Con volúmenes muy altos por cliente, particionar por `customer_id` en una cola
  (Kafka con key = cliente) para procesar en serie por cliente sin bloqueos de BD.

### D2. Idempotencia por `requestReference` (RF05)

- **Problema:** reintentos del canal no deben duplicar solicitudes ni consumir cupo dos veces; la misma referencia
  con otros datos no debe sobrescribir nada en silencio.
- **Alternativas:** header `Idempotency-Key` separado; tabla de idempotencia con respuesta cacheada; restricción
  única + huella del contenido.
- **Decisión:** `UNIQUE (request_reference)` en BD + huella SHA-256 de los datos de negocio (montos normalizados
  para que `1000` y `1000.00` sean iguales).
  - Mismos datos → **200** con el resultado del primer procesamiento y `Idempotent-Replayed: true`.
  - Datos distintos → **409 Conflict** con el `existingId`; la original queda intacta.
  - Dos llegadas simultáneas de la misma referencia: la restricción única deja ganar a una; la otra revierte su
    transacción (incluida la reserva de cupo) y devuelve el resultado de la ganadora.
- **Ventaja:** la referencia ya viene del canal (no se agrega contrato nuevo) y la unicidad la impone la BD aunque
  haya concurrencia. **Trade-off:** la unicidad es global, no por canal (ver supuestos).
- **Verificación:** pruebas de reintento secuencial (mismo id, cupo no consumido de nuevo, se conserva incluso un
  rechazo), conflicto 409 con original sin cambios, y 10 envíos simultáneos de la misma referencia → 1 registro,
  1 respuesta 201, 9 respuestas 200 con el mismo id, cupo consumido una vez.

### D3. Persistencia: R2DBC + H2 en memoria

- **Problema:** demostrar RF04/RF05 en ejecución sin exigir infraestructura al evaluador.
- **Alternativas:** colecciones en memoria (no demuestran transacciones ni restricciones reales); PostgreSQL con
  Docker/Testcontainers (más realista, pero obliga a tener Docker).
- **Decisión:** H2 vía R2DBC (stack reactivo de punta a punta) con SQL explícito en `DatabaseClient`, para que la
  lógica de concurrencia sea visible y portable a PostgreSQL sin cambios.
- **Trade-off:** los datos se pierden al reiniciar; y el driver `r2dbc-h2` ejecuta de forma bloqueante en el hilo que
  se suscribe. Si dos solicitudes que compiten por el mismo bloqueo de fila corrieran en el **mismo** hilo del event
  loop de Netty se bloquearían entre sí, por eso el servicio usa `subscribeOn(Schedulers.boundedElastic())`.
  Con `r2dbc-postgresql` (no bloqueante) eso se eliminaría.
- **A otra escala:** PostgreSQL + Flyway para migraciones, Testcontainers en las pruebas.

### D4. Validaciones (RF02) y rechazos de negocio (RF03)

- Entradas inválidas → **400** con todos los campos fallidos, y **no se persisten** (no son solicitudes "procesables
  como válidas").
- Incumplir una regla de negocio no es un error de la petición: se responde **201** con `status: REJECTED` y la
  razón, y queda persistida.
- Orden de evaluación: score → capacidad por ingreso → cupo diario. Se reporta la **primera** regla incumplida y el
  cupo solo se toca si las reglas individuales pasan (un rechazo nunca consume cupo).
- Montos en `BigDecimal` con 2 decimales (`@Digits(fraction = 2)`), nunca `double`.

### D5. Día calendario

`businessDate` se calcula con la zona `America/Bogota` (configurable en `loans.business-zone`), porque el límite es
en COP y "día calendario" para un banco colombiano no debería depender de la zona del servidor (UTC en la nube).
`processedAt` se expone en UTC (ISO-8601). Se inyecta un `Clock` para poder controlar el tiempo.

### D6. Logs sin datos sensibles

Logs estructurados `event=... clave=valor` para procesado, reintento, conflicto e inválido. Nunca se registran
ingreso, puntaje ni montos; el `customerId` se enmascara (`******3456`). En validación solo se registran los
**nombres** de los campos fallidos, no sus valores. Los errores 500 no exponen trazas al cliente.

### D7. Configuración

Umbrales de negocio en `application.properties` (`loans.min-credit-score`, `loans.max-income-multiplier`,
`loans.daily-approved-limit`, `loans.business-zone`), validados al arrancar. No hay secretos: H2 en memoria con
usuario `sa` sin contraseña, solo para ejecución local.

---

## 5. Supuestos

| Supuesto | Impacto |
|---|---|
| `requestReference` es única globalmente (no por canal). | Si dos canales pudieran generar la misma referencia, habría que hacer la llave `(channel, requestReference)` y agregar el campo `channel`. |
| Las entradas inválidas no se almacenan. | No quedan en el historial; solo en logs (sin valores). |
| Los límites son inclusivos: `score >= 650`, `monto <= ingreso × 8`, total del día `<= 15.000.000`. | Tal como lo dice el enunciado. |
| Toda solicitud está en COP. | No se agregó campo de moneda. |
| Una solicitud rechazada se responde con 201 (fue registrada y procesada). | El cliente distingue aprobado/rechazado por `status`, no por código HTTP. |
| El reintento con datos iguales responde 200 (no 201). | Permite al canal saber que no se creó nada nuevo; el cuerpo es idéntico al original. |

## 6. Fuera de alcance / qué haría con más tiempo

- PostgreSQL + Flyway + Testcontainers (y `docker-compose.yml`) en lugar de H2 en memoria.
- Prueba de cambio de día con un `Clock` controlado (el diseño ya lo permite; hoy el límite por día se verifica
  indirectamente porque la llave del cupo es `(cliente, fecha)`).
- OpenAPI/Swagger generado (`springdoc`), autenticación del canal y campo `channel`.
- Correlation-id por petición en los logs y métricas (aprobadas/rechazadas/conflictos) en Actuator/Micrometer.
- Reversión de cupo si una aprobación se anula (no lo pide el enunciado).

---

## 7. Uso de Inteligencia Artificial

Se usó **Claude Code (Anthropic)** como asistente durante la prueba.

| Aspecto | Detalle |
|---|---|
| **Para qué** | Lectura del enunciado, propuesta de diseño (alternativas de concurrencia e idempotencia), generación del código base, pruebas y este README. |
| **Qué aportó** | La propuesta del `UPDATE` condicional atómico, la huella SHA-256 para RF05, la estructura por capas y la batería de pruebas de concurrencia por HTTP. |
| **Cómo se validó** | (1) Suite completa en verde (`./mvnw test`, 35 pruebas). (2) **Prueba de mutación**: se rompió a propósito la reserva de cupo (lectura y luego escritura) y las pruebas de concurrencia fallaron, demostrando que sí detectan la carrera. (3) Ejecución manual con curl: alta, reintento (200), conflicto (409), entrada inválida (400), consulta y revisión de los logs. |
| **Qué se corrigió / ajustó** | Versión de Spring Boot mal escrita en el `pom.xml` (`4.0.8.RELEASE` → `4.0.8`); se quitó la dependencia `h2console` (servlet, no aplica a WebFlux); se normalizaron los montos a escala 2 porque `BigDecimal.equals` hacía que la respuesta inicial y la consultada no coincidieran; se truncó `processedAt` a microsegundos (precisión de la BD) para que el reintento devuelva exactamente lo mismo; se agregó `subscribeOn(boundedElastic)` por el comportamiento bloqueante de `r2dbc-h2`; se agregó un handler para `ResponseStatusException` que de otra forma terminaba como 500. |
| **Qué se descartó** | Lock en memoria por cliente (no funciona con varias instancias) y PostgreSQL con Docker (agregaba un requisito para ejecutar). |
| **Información sensible** | Solo se compartió el enunciado de la prueba. No se usaron datos reales, credenciales ni información de la empresa; todos los datos de ejemplo y prueba son ficticios. |
