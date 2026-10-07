package com.krishna.order_platform.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String TOPIC = "order.created";

    @Mock OutboxRepository outboxRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outboxRepository, kafkaTemplate,
                TransactionOperations.withoutTransaction(), clock, 50);
    }

    private static OutboxEvent row(UUID aggregateId, String payload) {
        return OutboxEvent.pending(UUID.randomUUID(), "Order", aggregateId,
                "OrderCreated", TOPIC, payload, NOW);
    }

    @Test
    void marksEveryRowPublishedWhenAllSendsSucceed() {
        OutboxEvent one = row(UUID.randomUUID(), "p1");
        OutboxEvent two = row(UUID.randomUUID(), "p2");
        when(outboxRepository.lockNextBatch(50)).thenReturn(List.of(one, two));
        when(kafkaTemplate.send(eq(TOPIC), eq(one.getAggregateId().toString()), eq("p1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(eq(TOPIC), eq(two.getAggregateId().toString()), eq("p2")))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishBatch();

        assertThat(one.getPublishedAt()).isEqualTo(NOW);
        assertThat(two.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void stopsAtFirstFailedSendSoLaterRowsNeverOvertake() {
        OutboxEvent one = row(UUID.randomUUID(), "p1");
        OutboxEvent two = row(UUID.randomUUID(), "p2");
        OutboxEvent three = row(UUID.randomUUID(), "p3");
        when(outboxRepository.lockNextBatch(50)).thenReturn(List.of(one, two, three));
        when(kafkaTemplate.send(eq(TOPIC), eq(one.getAggregateId().toString()), eq("p1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(eq(TOPIC), eq(two.getAggregateId().toString()), eq("p2")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher().publishBatch();

        assertThat(one.getPublishedAt()).isEqualTo(NOW);
        assertThat(two.getPublishedAt()).isNull();
        assertThat(three.getPublishedAt()).isNull();
        verify(kafkaTemplate, never()).send(eq(TOPIC), eq(three.getAggregateId().toString()), any());
    }
}