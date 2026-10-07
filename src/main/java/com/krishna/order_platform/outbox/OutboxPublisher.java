package com.krishna.order_platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 15;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           TransactionOperations transactionOperations,
                           Clock clock,
                           @Value("${outbox.publisher.batch-size:50}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionOperations = transactionOperations;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    // fixedDelay: the next poll starts only after the previous one finishes, so polls never overlap in this instance.
    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:500}")
    public void poll() {
        transactionOperations.executeWithoutResult(status -> publishBatch());
    }

    /**
     * Must run inside a transaction: the row locks from lockNextBatch last until it ends, and the
     * published_at updates are flushed when it commits. Sends run in id order, and the batch stops at the
     * first failure, so a later event for the same order can never overtake an earlier one.
     * Rows already marked before the failure still commit; the failed row and everything after it retry next poll.
     */
    void publishBatch() {
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(batchSize);
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing outbox event {}; stopping batch", event.getEventId());
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("Failed to publish outbox event {} ({}); will retry next poll",
                        event.getEventId(), event.getEventType(), e);
                return;
            }
            // Only after the broker acknowledged. A crash right here means a resend, which is the at-least-once case.
            event.markPublished(clock.instant());
        }
    }
}