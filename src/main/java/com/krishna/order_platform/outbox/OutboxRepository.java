package com.krishna.order_platform.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Locks and returns the oldest unpublished rows, in id order.
     * Plain FOR UPDATE (not SKIP LOCKED): a second publisher instance waits instead of
     * skipping ahead, so a later event for the same order can never be sent before an earlier one.
     * Must be called inside a transaction; the row locks last until it ends.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :limit
            FOR UPDATE
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);
}