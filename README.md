# Solicitudes de préstamo – Prueba técnica Backend Java

Un servicio pequeño para **registrar y consultar solicitudes de préstamo**, hecho con Java 17, Spring Boot 4 y Spring WebFlux.

Lo interesante del ejercicio no es guardar y consultar, sino dos problemas del mundo real:

- **La misma solicitud puede llegar varias veces** (el canal reintenta si se cae la red). No se debe crear otra ni aprobarla dos veces. → **RF05**
- **El mismo cliente puede pedir varios préstamos al mismo tiempo.** Aunque lleguen en el mismo segundo, nunca se le deben aprobar más de 15.000.000 COP en el día. → **RF04**

---

## Índice

1. [Cómo ejecutarlo](#1-cómo-ejecutarlo)
2. [Cómo probarlo](#2-cómo-probarlo)
3. [La API](#3-la-api)
4. [Cómo funciona por dentro](#4-cómo-funciona-por-dentro)
5. [Checklist de requisitos](#5-checklist-de-requisitos)
6. [Decisiones técnicas](#6-decisiones-técnicas)
7. [Supuestos](#7-supuestos)
8. [Lo que quedó por fuera](#8-lo-que-quedó-por-fuera)
9. [Uso de Inteligencia Artificial](#9-uso-de-inteligencia-artificial)

---

## 1. Cómo ejecutarlo

**Solo necesitas Java 17 o superior.** No hace falta instalar Maven (el proyecto trae su propio Maven, el *wrapper* `mvnw`), ni Docker, ni una base de datos: se usa H2 en memoria.

### Opción A: desde la terminal

```bash
# Windows (PowerShell o cmd)
mvnw.cmd spring-boot:run

# Linux / macOS / Git Bash
./mvnw spring-boot:run
```

### Opción B: desde IntelliJ IDEA (funciona igual en Community)

1. **File → Open** y elige la carpeta del proyecto. IntelliJ reconoce el `pom.xml` y descarga las dependencias.
2. Revisa que el proyecto use Java 17: **File → Project Structure → Project → SDK**.
3. Abre `src/main/java/com/ias/loans/LoanRequestsApplication.java` y dale a la flecha verde ▶ junto a la clase.

Cuando en la consola aparezca `Netty started on port 8080`, la API ya está lista en `http://localhost:8080`.
Para comprobarlo: `GET http://localhost:8080/actuator/health` debe responder `{"status":"UP"}`.

> La base de datos vive en memoria: **cada vez que reinicias la app, empieza vacía**.

---

## 2. Cómo probarlo

### Pruebas automáticas (35 pruebas, unos 20 segundos)

```bash
mvnw.cmd test          # Windows
./mvnw test            # Linux / macOS
```

En IntelliJ: clic derecho sobre `src/test/java` → **Run 'All Tests'**.

Hay dos tipos de pruebas:

- **Unitarias** (`LoanEvaluatorTest`): prueban las reglas de puntaje e ingreso con Java puro, sin levantar nada.
- **De integración** (`LoanRequestApiTest`, `ConcurrencyTest`): levantan la aplicación completa en un puerto libre y le hacen
  peticiones HTTP de verdad, como haría Postman. Así se prueba todo el camino: validaciones, reglas, base de datos y respuestas.

| Lo que pide la prueba | Dónde se prueba |
|---|---|
| Una solicitud aprobada | `LoanRequestApiTest.approvesValidRequestAndAllowsQueryingItByIdAndReference` |
| Una rechazada por regla de negocio | `rejectsWhenCreditScoreIsTooLow`, `rejectsWhenAmountExceedsEightTimesMonthlyIncome`, `rejectsWhenDailyApprovedTotalWouldExceedLimit` |
| Una entrada inválida | `rejectsInvalidInputWithControlledError` (9 casos) y `rejectsMalformedJson` |
| Repetición de una `requestReference` | `repeatedReferenceWithSameData...`, `repeatedReferenceKeepsFirstResult...`, `repeatedReferenceWithDifferentData...` |
| Dos solicitudes del mismo cliente compitiendo por el límite | `ConcurrencyTest`: 2 de 10M al tiempo (repetida 5 veces) y 40 de 1M al tiempo |
| La misma referencia llegando varias veces al tiempo | `ConcurrencyTest.sameReferenceArrivingSimultaneouslyIsProcessedOnce` |

### Postman

Importa [`postman/prueba-tecnica-prestamos.postman_collection.json`](postman/prueba-tecnica-prestamos.postman_collection.json).
Con la app corriendo, ejecuta las peticiones de arriba hacia abajo (cada una explica qué prueba en su descripción) o todas de una con **Run collection**.

- Recorre RF01 a RF06 en orden, con 42 verificaciones automáticas.
- Incluye el caso de **dos solicitudes enviadas al mismo tiempo** (RF04). Como Postman normalmente manda una petición a la vez,
  ese caso dispara las dos desde el script *Pre-request*. Mira la consola de Postman para ver ambas respuestas.
- Se puede ejecutar muchas veces sin reiniciar la app: cada corrida usa referencias y clientes nuevos.

---

## 3. La API

| Método | Ruta | Para qué |
|---|---|---|
| `POST` | `/api/v1/loan-requests` | Registrar una solicitud |
| `GET` | `/api/v1/loan-requests/{id}` | Consultar por el id que generó el sistema |
| `GET` | `/api/v1/loan-requests?requestReference=...` | Consultar por la referencia que mandó el canal |

### Registrar: `POST /api/v1/loan-requests`

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

Qué puede responder:

| Código | Cuándo |
|---|---|
| **201 Created** | Solicitud nueva. Puede quedar `APPROVED` o `REJECTED`: un rechazo por reglas del banco es una respuesta normal, no un error. |
| **200 OK** + header `Idempotent-Replayed: true` | Esa referencia ya existía con los mismos datos (un reintento). Se devuelve el resultado original y no se crea nada. |
| **409 Conflict** | Esa referencia ya existía, pero con datos diferentes. No se toca la original. |
| **400 Bad Request** | Datos inválidos (por ejemplo, monto 0 o plazo de 3 meses). No se guarda nada. |

Respuesta (igual para POST y GET):

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

- `status`: `APPROVED` o `REJECTED`.
- `rejectionReason`: si fue rechazada, el código de la razón: `CREDIT_SCORE_TOO_LOW`, `AMOUNT_EXCEEDS_INCOME_CAPACITY` o `DAILY_LIMIT_EXCEEDED`.
- `rejectionDetail`: la misma razón explicada en texto.
- `businessDate`: el día (hora Colombia) en el que cuenta para el límite diario.
- `processedAt`: fecha y hora exacta del procesamiento, en UTC.

### Errores

Todos los errores tienen el mismo formato (el estándar *problem+json*), para que quien consuma la API los maneje siempre igual.
En los errores de validación se listan **todos** los campos que fallaron, no solo el primero:

```json
{
  "type": "https://api.example.com/problems/validation-error",
  "title": "Solicitud invalida",
  "status": 400,
  "detail": "Uno o mas campos no cumplen las validaciones",
  "errors": [
    { "field": "creditScore", "message": "debe estar entre 0 y 1000" },
    { "field": "termMonths",  "message": "debe estar entre 6 y 60 meses" }
  ]
}
```

---

## 4. Cómo funciona por dentro

### Las carpetas

```
src/main/java/com/ias/loans
├── domain/                 Las reglas del negocio en Java puro (sin Spring ni base de datos)
│   ├── LoanApplication       los datos que manda el canal (+ su "huella" para detectar repetidas)
│   ├── LoanEvaluator         reglas de puntaje e ingreso
│   ├── LoanRequest           una solicitud ya procesada
│   └── LoanStatus, RejectionReason
├── application/            El flujo: qué pasa cuando llega una solicitud
│   └── LoanRequestService    ← empieza a leer por aquí
├── infrastructure/
│   ├── web/                Lo que tiene que ver con HTTP: controller, JSON de entrada/salida, errores
│   └── persistence/        Lo que tiene que ver con la base de datos (SQL escrito a mano)
└── config/                 Configuración: límites del negocio y zona horaria
src/main/resources
├── application.properties  Configuración (los umbrales del negocio están aquí)
└── schema.sql              Las dos tablas
```

La idea es que **las reglas del negocio no dependan de detalles técnicos**: `LoanEvaluator` no sabe nada de HTTP ni de bases de datos.
Por eso se prueba con una prueba unitaria simple. Es una organización por capas: no es una arquitectura hexagonal completa,
porque el servicio usa directamente los repositorios en vez de interfaces (ver [lo que quedó por fuera](#8-lo-que-quedó-por-fuera)).

### Las dos tablas

- **`loan_request`**: cada solicitud procesada. La columna `request_reference` es **única**: la base de datos no permite dos con la misma referencia.
- **`customer_daily_exposure`**: una fila por **cliente y día** con el total que se le ha aprobado. Es como una "libreta" por cliente.
  Al día siguiente simplemente se usa otra fila, así que el límite se reinicia solo.

### Qué pasa cuando llega un `POST`

1. **Validación (RF02).** Spring revisa las anotaciones de `LoanRequestBody` (`@Positive`, `@Min(6)`, `@Max(60)`...).
   Si algo falla, responde 400 y no se guarda nada.
2. **¿Ya existe esa referencia? (RF05).** Se busca en la base de datos:
   - Si existe con los **mismos datos**: es un reintento y se devuelve el resultado original (200).
   - Si existe con **otros datos**: 409.
   - Para saber si son "los mismos datos" se compara una **huella** (un hash SHA-256 de todos los campos) que se guardó con la solicitud.
3. **Reglas individuales (RF03).** ¿Puntaje ≥ 650? ¿Monto ≤ 8 × ingreso? Si alguna falla, se guarda como `REJECTED`
   y **no se toca el cupo diario**.
4. **Cupo diario (RF03 + RF04).** Si pasó las reglas, se intenta sumar el monto al total del cliente con un solo `UPDATE`
   (explicado abajo). Si había cupo queda `APPROVED`; si no, `REJECTED` por `DAILY_LIMIT_EXCEEDED`.
5. **Se guarda la solicitud (RF01)** con su id, sus datos, el resultado y la fecha/hora. Los pasos 4 y 5 van en **una sola transacción**:
   si guardar falla, la suma al cupo se deshace.

### El problema de las solicitudes al mismo tiempo (RF04), explicado con un ejemplo

Juan lleva 0 aprobado hoy y llegan **al mismo tiempo** dos solicitudes suyas de 10 millones (A y B).

Si el programa hiciera "**leer** el total → **comparar** → **guardar**" en tres pasos, pasaría esto:

| Momento | Solicitud A | Solicitud B | Total |
|---|---|---|---|
| 1 | Lee: Juan lleva 0 | | 0 |
| 2 | | Lee: Juan lleva 0 | 0 |
| 3 | 0 + 10 ≤ 15 → aprueba | | 0 |
| 4 | | 0 + 10 ≤ 15 → aprueba | 0 |
| 5 | Guarda 10 | | 10 |
| 6 | | Suma 10 más | **20** ❌ |

El error es que B leyó el total antes de que A lo actualizara. La solución fue hacer los tres pasos en **una sola instrucción SQL**:

```sql
UPDATE customer_daily_exposure
   SET approved_total = approved_total + :amount          -- súmale el monto
 WHERE customer_id = :customerId AND business_date = :day -- a la fila de este cliente para hoy
   AND approved_total + :amount <= :limit                 -- pero SOLO si no se pasa del límite
```

Mientras A ejecuta ese `UPDATE`, la base de datos **bloquea la fila de Juan** y B tiene que esperar su turno.
Cuando B entra, ya ve 10 millones, la condición no se cumple y el `UPDATE` no modifica nada.
El programa mira cuántas filas se modificaron: **1 → aprobada, 0 → rechazada**. Ver `DailyExposureRepository.tryReserve`.

### Un detalle de WebFlux que vale la pena saber

WebFlux funciona con **pocos hilos que nunca deberían quedarse esperando** (por eso los métodos devuelven `Mono`, que significa
"un resultado que llegará después"). El driver de H2, aunque se presenta como reactivo, por dentro sí se queda esperando a la base de datos.
Por eso el servicio usa `subscribeOn(Schedulers.boundedElastic())`, que manda ese trabajo a otro grupo de hilos hecho para esperar.
Con PostgreSQL y su driver reactivo esa línea sobraría.

---

## 5. Checklist de requisitos

| Requisito del enunciado | Estado | Dónde |
|---|---|---|
| Datos mínimos: requestReference, customerId, requestedAmount, termMonths, monthlyIncome, creditScore | ✅ | `LoanRequestBody` |
| **RF01** Registrar con id propio, datos, resultado y fecha/hora de procesamiento | ✅ | `LoanRequestService`, tabla `loan_request` |
| **RF02** Monto > 0, plazo 6–60, ingreso > 0, puntaje 0–1000, con errores claros | ✅ | `LoanRequestBody` + `ApiExceptionHandler` |
| **RF03** Puntaje ≥ 650 | ✅ | `LoanEvaluator` |
| **RF03** Monto ≤ ingreso × 8 | ✅ | `LoanEvaluator` |
| **RF03** Total aprobado del cliente en el día ≤ 15.000.000 | ✅ | `DailyExposureRepository.tryReserve` |
| **RF03** Las rechazadas se guardan con una razón general | ✅ | `rejectionReason` + `rejectionDetail` |
| **RF04** Con solicitudes simultáneas nunca se superan los 15M | ✅ | `UPDATE` condicional + `ConcurrencyTest` |
| **RF05** Una referencia repetida no crea otra solicitud ni cambia el cupo | ✅ | columna única + `handleRepeated` |
| **RF05** Se conserva el resultado del primer procesamiento | ✅ | se devuelve lo guardado, aunque hoy el resultado fuera distinto |
| **RF05** Misma referencia con otros datos: controlado y sin cambiar la original | ✅ | 409 + huella SHA-256 |
| **RF06** Consultar por id o por requestReference, con datos, estado, razón y fecha | ✅ | `LoanRequestController` |
| Java + Spring Boot + Spring WebFlux | ✅ | `pom.xml` |
| Persistencia que permita demostrar RF04 y RF05 | ✅ | H2 vía R2DBC, con transacciones y restricciones reales |
| Contratos HTTP claros y errores consistentes | ✅ | [sección 3](#3-la-api) |
| Sin secretos ni datos sensibles en el repositorio | ✅ | solo H2 local, datos de ejemplo ficticios |
| Logs útiles sin información sensible | ✅ | `event=...`, cliente enmascarado, sin montos ni puntaje |
| Se ejecuta localmente con instrucciones | ✅ | [sección 1](#1-cómo-ejecutarlo) |
| Pruebas mínimas pedidas (aprobada, rechazada, inválida, repetida, concurrencia) | ✅ | [sección 2](#2-cómo-probarlo) |
| Decisiones técnicas documentadas | ✅ | [sección 6](#6-decisiones-técnicas) |
| Registro de uso de IA | ✅ | [sección 9](#9-uso-de-inteligencia-artificial) |
| Historial de commits | ✅ | `git log` |

---

## 6. Decisiones técnicas

Para cada decisión: el problema, qué alternativas había, qué elegí y por qué, qué se pierde, cómo comprobé que funciona
y qué cambiaría si la escala fuera otra.

### D1. Cómo evitar aprobar más de 15M con solicitudes simultáneas (RF04)

- **Problema:** "leer el total → comparar → guardar" deja un hueco en el que dos solicitudes leen el mismo total y las dos se aprueban (ver el ejemplo de la [sección 4](#el-problema-de-las-solicitudes-al-mismo-tiempo-rf04-explicado-con-un-ejemplo)).
- **Alternativas que consideré:**
  - *`synchronized` o un lock en memoria por cliente:* es lo más simple, pero solo funciona si hay un único servidor. Con dos instancias del servicio, cada una tiene su propia memoria y el problema vuelve.
  - *`SELECT ... FOR UPDATE` y decidir en Java:* funciona, pero son más pasos y más código dentro de la transacción.
  - *Bloqueo optimista (columna de versión + reintentos):* funciona, pero si muchas solicitudes del mismo cliente llegan juntas, hay muchos reintentos.
  - *Un solo `UPDATE` con la condición adentro.*
- **Decisión:** el `UPDATE` condicional. Mirar, comparar y sumar ocurren en una sola instrucción que la base de datos no deja interrumpir.
- **Ventaja:** quien garantiza la regla es la base de datos, así que sigue funcionando con varios servidores. Además, solo esperan entre sí las solicitudes **del mismo cliente**; clientes distintos no se bloquean.
- **Lo que se pierde:** hay que mantener una tabla extra con el acumulado, y debe actualizarse en la misma transacción que la solicitud (así está hecho).
- **Cómo lo verifiqué:** `ConcurrencyTest` manda solicitudes HTTP en paralelo: 2 de 10M → exactamente 1 aprobada (repetida 5 veces); 40 de 1M → exactamente 15 aprobadas y el total queda en 15M. Además hice una **prueba de mutación**: cambié el `UPDATE` por la versión de tres pasos y 6 de las 11 pruebas de concurrencia fallaron (se aprobaban 20M). Eso confirma que las pruebas sí detectan el problema.
- **Si la escala fuera otra:** PostgreSQL con el mismo `UPDATE`. Si un mismo cliente generara muchísimas solicitudes, las pondría en una cola (por ejemplo Kafka, usando el cliente como llave) para procesarlas en orden sin bloqueos.

### D2. Cómo manejar la misma `requestReference` repetida (RF05)

- **Problema:** un reintento no debe crear otra solicitud ni gastar cupo dos veces, y una referencia con otros datos no debe pisar la original.
- **Alternativas:** pedir un header aparte (`Idempotency-Key`); guardar las respuestas en una tabla aparte; o usar la referencia como llave única + una huella de los datos.
- **Decisión:** columna **única** en `request_reference` + **huella SHA-256** de los datos guardada con la solicitud.
  - Mismos datos → 200 con el resultado original.
  - Datos distintos → 409, y la original no se toca.
  - Si la misma referencia llega dos veces **al mismo tiempo**, la base de datos solo deja guardar una. La otra recibe un error, su transacción se deshace (incluida la suma al cupo) y responde con lo que guardó la primera.
- **Ventaja:** se usa la referencia que el canal ya manda, sin cambiar el contrato, y la base de datos impide duplicados aunque haya concurrencia.
- **Lo que se pierde:** la referencia tiene que ser única entre todos los canales (ver [supuestos](#7-supuestos)).
- **Cómo lo verifiqué:** pruebas de reintento (mismo id y el cupo no se vuelve a gastar), de conflicto (409 y la original igual), y 10 envíos simultáneos de la misma referencia → 1 solo registro y el cupo gastado una vez.
- **Si la escala fuera otra:** agregaría el canal a la llave (`canal + referencia`) y una fecha de expiración para las referencias.

### D3. Base de datos: H2 en memoria con R2DBC

- **Problema:** había que demostrar RF04 y RF05 funcionando con transacciones reales, pero que fuera fácil de ejecutar para quien revise.
- **Alternativas:** listas en memoria (no tienen transacciones ni restricciones únicas reales); PostgreSQL con Docker (más realista, pero obliga a instalar Docker).
- **Decisión:** H2 en memoria, conectado con R2DBC (la versión reactiva de JDBC, para que todo el flujo sea WebFlux). El SQL está escrito a mano con `DatabaseClient`, para que se vea exactamente qué hace, y es SQL estándar que funciona igual en PostgreSQL.
- **Ventaja:** solo se necesita Java para ejecutarlo y probarlo.
- **Lo que se pierde:** los datos se borran al reiniciar, y el driver de H2 bloquea hilos por dentro (de ahí el `boundedElastic` explicado en la [sección 4](#un-detalle-de-webflux-que-vale-la-pena-saber)).
- **Cómo lo verifiqué:** las pruebas de concurrencia corren contra esta misma base de datos.
- **Si la escala fuera otra:** PostgreSQL + Flyway (para versionar el esquema) + Testcontainers (para probar contra un PostgreSQL real).

### D4. Inválida (400) vs. rechazada (201)

- **Problema:** el enunciado distingue entre una solicitud que "no puede procesarse como válida" (RF02) y una que se evalúa y se rechaza (RF03).
- **Decisión:**
  - **Inválida** (monto 0, plazo de 3 meses...): el cliente de la API mandó algo mal → **400** y **no se guarda**.
  - **Rechazada** (puntaje bajo, sin cupo...): la petición estaba bien y el banco dijo que no → se **guarda** como `REJECTED` y responde **201**. Es un resultado del negocio, no un error.
- **Orden de las reglas:** puntaje → ingreso → cupo diario. Se informa la primera que falla y el cupo solo se toca si pasaron las otras dos, así **un rechazo nunca gasta cupo**.
- **Montos:** siempre `BigDecimal` con 2 decimales, nunca `double`, porque `double` tiene errores de redondeo que no se aceptan con dinero.
- **Cómo lo verifiqué:** pruebas parametrizadas con 9 entradas inválidas y pruebas de cada tipo de rechazo, incluidos los límites exactos (650 y 8 veces el ingreso).

### D5. Qué significa "el día"

- **Problema:** el límite es "durante el día calendario", pero ¿el día de dónde? Un servidor en la nube suele estar en UTC: a las 7 p. m. en Colombia ya es "mañana" en UTC y el cupo se reiniciaría antes de tiempo.
- **Decisión:** el día se calcula con la zona `America/Bogota`, configurable en `loans.business-zone`. La hora exacta (`processedAt`) se responde en UTC.
- **Lo que se pierde:** si el banco opera en varios países, habría que definir la zona por cliente o por país.
- **Extra:** la hora se toma de un `Clock` inyectado, así que en una prueba se puede simular cualquier fecha.

### D6. Logs sin datos sensibles

- Cada evento importante deja un log fácil de buscar: `event=loan_request_processed`, `loan_request_replayed`, `loan_request_conflict`, `loan_request_invalid`.
- **Nunca** se registran el ingreso, el puntaje ni los montos, y el cliente sale enmascarado (`****0001`).
- En los errores de validación solo se registra **qué campo** falló, no el valor que mandaron.
- Si ocurre un error inesperado (500), la traza queda en el log del servidor, pero al cliente nunca se le muestra.

### D7. Configuración

- Los umbrales (650, 8, 15.000.000 y la zona horaria) están en `application.properties`, no fijos en el código: si el negocio los cambia, no hay que tocar Java.
- Spring valida esa configuración al arrancar, así que si falta un valor la app no inicia.
- No hay secretos en el repositorio: H2 local con usuario `sa` sin contraseña, solo para correr en tu máquina.

---

## 7. Supuestos

El enunciado deja algunas cosas abiertas. Esto es lo que asumí y qué implica:

| Supuesto | Qué implica |
|---|---|
| La `requestReference` es única entre **todos** los canales. | Si dos canales pudieran generar la misma referencia, habría que agregar un campo `channel` y usar `canal + referencia` como llave. |
| Las solicitudes inválidas (RF02) **no se guardan**. | No quedan en el historial, solo en los logs (sin sus valores). |
| Los límites son **inclusivos**: puntaje ≥ 650, monto ≤ ingreso × 8, total del día ≤ 15.000.000. | Así lo dice el enunciado: 650 aprueba y llegar exactamente a 15M también. |
| Todo está en pesos colombianos (COP). | No hay campo de moneda. |
| Una solicitud rechazada responde **201**. | Quien consume la API distingue aprobada/rechazada por `status`, no por el código HTTP. |
| Un reintento con los mismos datos responde **200** (no 201). | El canal puede saber que no se creó nada nuevo. El cuerpo es idéntico al original. |
| Si una referencia aprobada se repite cuando el cliente ya no tiene cupo, se sigue respondiendo `APPROVED`. | Es lo que pide RF05: conservar el resultado del primer procesamiento, no volver a evaluar. |

---

## 8. Lo que quedó por fuera

Cosas que no hice para mantener la solución simple dentro de las 2 horas, y cómo las haría:

- **PostgreSQL en vez de H2:** agregar `r2dbc-postgresql`, Flyway para el esquema y Testcontainers en las pruebas. El SQL no cambia y se podría quitar el `boundedElastic`.
- **Interfaces entre el servicio y los repositorios** (arquitectura hexagonal completa): crear interfaces en `application/` (por ejemplo `LoanRequestStore`) y que los repositorios SQL las implementen. Sirve cuando hay más de una forma de guardar o para probar el servicio sin base de datos.
- **Prueba de cambio de día:** el diseño ya lo permite (la llave del cupo es cliente + fecha y la hora sale de un `Clock`), pero no hay una prueba que simule pasar a medianoche.
- **Campo `channel`** para saber de qué canal llegó cada solicitud.
- **Documentación OpenAPI/Swagger** generada automáticamente (`springdoc`).
- **Seguridad:** autenticación de los canales que llaman a la API.
- **Observabilidad:** un id de correlación por petición en los logs y métricas (aprobadas, rechazadas, conflictos).

---

## 9. Uso de Inteligencia Artificial

Usé **Claude Code** (el asistente de programación de Anthropic) durante toda la prueba.

**Para qué lo usé**
- Leer el enunciado y proponer cómo resolver RF04 y RF05, comparando alternativas.
- Generar la mayor parte del código, las pruebas automáticas, la colección de Postman y este README.
- Que me explicara la solución paso a paso (WebFlux, la organización por capas, las pruebas de integración y cada decisión técnica), porque varios de esos temas eran nuevos para mí.

**Qué aportó**
- La idea del `UPDATE` condicional para el límite diario y la huella SHA-256 para detectar referencias repetidas con otros datos.
- La estructura del proyecto y las pruebas de concurrencia que mandan peticiones HTTP en paralelo.

**Cómo validé el resultado**
- Ejecuté las 35 pruebas automáticas y la colección de Postman (42 verificaciones) contra la app corriendo.
- La **prueba de mutación** de la decisión D1: se rompió a propósito el `UPDATE` y las pruebas de concurrencia fallaron, lo que demuestra que sí detectan el problema.
- Probé a mano los casos principales (aprobada, rechazada, inválida, repetida, conflicto, dos solicitudes al mismo tiempo) y revisé los logs.
- Revisé el código parte por parte con las explicaciones, para poder sustentarlo.

**Qué se corrigió o ajustó en el camino**
- La versión de Spring Boot estaba mal escrita en el `pom.xml` y el proyecto no compilaba.
- Se quitó una dependencia (`h2console`) que no aplica a WebFlux.
- Los montos se dejan siempre con 2 decimales: sin eso, la respuesta del POST y la del GET no coincidían (`1000` vs `1000.00`).
- `processedAt` se corta a microsegundos (la precisión de la base de datos) para que un reintento devuelva exactamente lo mismo.
- Se agregó `subscribeOn(boundedElastic)` por la forma en que el driver de H2 bloquea hilos.
- Faltaba manejar algunos errores HTTP que terminaban como 500.
- La colección de Postman reutilizaba referencias si se ejecutaba varias veces; se corrigió para que cada corrida use valores nuevos.
- Se reescribió el servicio en pasos más fáciles de leer y se simplificaron los comentarios y este README.

**Qué se descartó**
- El lock en memoria por cliente (no funciona con varios servidores).
- PostgreSQL con Docker (agregaba un requisito más para ejecutar).
- Convertirlo a arquitectura hexagonal completa: con un solo tipo de base de datos agregaba complejidad sin beneficio claro.

**Información sensible**
- Solo compartí el enunciado de la prueba. No usé datos reales, credenciales ni información de la empresa; todos los datos de ejemplo y de prueba son inventados.
