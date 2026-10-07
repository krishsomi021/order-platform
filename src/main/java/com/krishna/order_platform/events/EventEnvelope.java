package com.krishna.order_platform.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID orderId,
        Instant occurredAt,
        T payload) {
}