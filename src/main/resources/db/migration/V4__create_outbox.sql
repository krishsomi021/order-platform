CREATE TABLE outbox (
                        id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        event_id       UUID         NOT NULL,
                        aggregate_type VARCHAR(64)  NOT NULL,
                        aggregate_id   UUID         NOT NULL,
                        event_type     VARCHAR(64)  NOT NULL,
                        topic          VARCHAR(255) NOT NULL,
                        payload        TEXT         NOT NULL,
                        created_at     TIMESTAMPTZ  NOT NULL,
                        published_at   TIMESTAMPTZ,
                        CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;