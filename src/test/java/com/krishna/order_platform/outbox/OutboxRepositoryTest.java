package com.krishna.order_platform.outbox;

import com.krishna.order_platform.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class OutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Autowired
    private OutboxRepository outboxRepository;

    private static OutboxEvent pendingEvent(UUID eventId) {
        return OutboxEvent.pending(eventId, "Order", UUID.randomUUID(),
                "OrderCreated", "order.created", "{}", NOW);
    }

    @Test
    void lockNextBatchReturnsOnlyUnpublishedRows() {
        OutboxEvent published = outboxRepository.save(pendingEvent(UUID.randomUUID()));
        OutboxEvent pending = outboxRepository.save(pendingEvent(UUID.randomUUID()));
        published.markPublished(NOW);
        outboxRepository.flush();

        List<OutboxEvent> batch = outboxRepository.lockNextBatch(10);

        assertThat(batch).extracting(OutboxEvent::getEventId)
                .containsExactly(pending.getEventId());
    }

    @Test
    void lockNextBatchReturnsOldestFirstAndHonoursLimit() {
        OutboxEvent first = outboxRepository.save(pendingEvent(UUID.randomUUID()));
        OutboxEvent second = outboxRepository.save(pendingEvent(UUID.randomUUID()));
        outboxRepository.save(pendingEvent(UUID.randomUUID()));
        outboxRepository.flush();

        List<OutboxEvent> batch = outboxRepository.lockNextBatch(2);

        assertThat(batch).extracting(OutboxEvent::getEventId)
                .containsExactly(first.getEventId(), second.getEventId());
    }

    @Test
    void duplicateEventIdIsRejectedByUniqueConstraint() {
        UUID eventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(pendingEvent(eventId));

        assertThatThrownBy(() -> outboxRepository.saveAndFlush(pendingEvent(eventId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_outbox_event_id");
    }
}