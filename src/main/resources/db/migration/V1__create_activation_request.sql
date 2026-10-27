CREATE TABLE activation_request (
    id                    UUID         PRIMARY KEY,
    external_id           VARCHAR(64)  NOT NULL,
    customer_id           VARCHAR(64)  NOT NULL,
    product_code          VARCHAR(64)  NOT NULL,
    client_id             VARCHAR(128) NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    attempts              INT          NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ  NOT NULL,
    processing_started_at TIMESTAMPTZ,
    failure_reason        VARCHAR(255),
    correlation_id        VARCHAR(64),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Single source of truth for duplicate prevention (ADR-0002)
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_activation_request_attempts CHECK (attempts >= 0)
);

-- Worker queue: only PENDING rows are indexed, so the index stays small
-- while finished rows (the vast majority) accumulate.
CREATE INDEX ix_ar_pending_due
    ON activation_request (next_attempt_at)
    WHERE status = 'PENDING';

-- Reconciliation of rows stuck in PROCESSING after an instance crash.
CREATE INDEX ix_ar_processing_lease
    ON activation_request (processing_started_at)
    WHERE status = 'PROCESSING';

-- Listing: every query is scoped by owner and ordered by created_at DESC.
CREATE INDEX ix_ar_client_created
    ON activation_request (client_id, created_at DESC);

CREATE INDEX ix_ar_client_status_created
    ON activation_request (client_id, status, created_at DESC);

CREATE INDEX ix_ar_client_customer_created
    ON activation_request (client_id, customer_id, created_at DESC);
