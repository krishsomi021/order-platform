package com.krishna.order_platform.outbox;

import com.krishna.order_platform.events.EventEnvelope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    public OutboxWriter(OutboxRepository outboxRepository, JsonMapper jsonMapper) {
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * MANDATORY: must join the caller's transaction, and throws if there isn't one.
     * That is the whole point of an outbox: the row commits or rolls back together with the business change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, String topic, EventEnvelope<?> envelope) {
        String payload = jsonMapper.writeValueAsString(envelope); // unchecked JacksonException on failure
        outboxRepository.save(OutboxEvent.pending(
                envelope.eventId(), aggregateType, envelope.orderId(),
                envelope.eventType(), topic, payload, envelope.occurredAt()));
    }
}