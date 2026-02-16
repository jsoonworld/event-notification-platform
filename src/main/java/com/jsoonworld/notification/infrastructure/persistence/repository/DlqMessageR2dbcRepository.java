package com.jsoonworld.notification.infrastructure.persistence.repository;

import com.jsoonworld.notification.infrastructure.persistence.entity.DlqMessageEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DlqMessageR2dbcRepository extends ReactiveCrudRepository<DlqMessageEntity, Long> {

    @Query("SELECT * FROM dlq_messages WHERE status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<DlqMessageEntity> findByStatusWithPaging(String status, int limit, int offset);

    @Modifying
    @Query("UPDATE dlq_messages SET status = :status, retried_at = NOW() WHERE id = :id")
    Mono<Void> updateStatus(Long id, String status);

    @Modifying
    @Query("UPDATE dlq_messages SET retry_count = retry_count + 1 WHERE id = :id")
    Mono<Void> incrementRetryCount(Long id);
}
