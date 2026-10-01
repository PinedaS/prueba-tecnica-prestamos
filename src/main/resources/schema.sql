-- Solicitudes procesadas. request_reference es UNICA: es la llave de idempotencia (RF05).
CREATE TABLE IF NOT EXISTS loan_request (
    id                  UUID           PRIMARY KEY,
    request_reference   VARCHAR(100)   NOT NULL,
    customer_id         VARCHAR(50)    NOT NULL,
    requested_amount    DECIMAL(19, 2) NOT NULL,
    term_months         INT            NOT NULL,
    monthly_income      DECIMAL(19, 2) NOT NULL,
    credit_score        INT            NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    rejection_reason    VARCHAR(50),
    business_date       DATE           NOT NULL,
    processed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_fingerprint VARCHAR(64)    NOT NULL,
    CONSTRAINT uk_loan_request_reference UNIQUE (request_reference)
);

CREATE INDEX IF NOT EXISTS ix_loan_request_customer_date ON loan_request (customer_id, business_date);

-- Total aprobado por cliente y dia. Es el punto de serializacion del limite diario (RF04):
-- la reserva de cupo es un UPDATE condicional atomico sobre esta fila.
CREATE TABLE IF NOT EXISTS customer_daily_exposure (
    customer_id    VARCHAR(50)    NOT NULL,
    business_date  DATE           NOT NULL,
    approved_total DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (customer_id, business_date)
);
