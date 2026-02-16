package com.jsoonworld.notification.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("dlq_messages")
@Getter
@Setter
@NoArgsConstructor
public class DlqMessageEntity {

    @Id
    private Long id;

    @Column("original_topic")
    private String originalTopic;

    @Column("event_id")
    private String eventId;

    @Column("payload")
    private String payload;

    @Column("error_message")
    private String errorMessage;

    @Column("retry_count")
    private Integer retryCount;

    @Column("status")
    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Column("retried_at")
    private Instant retriedAt;

    public DlqMessageEntity(String originalTopic, String eventId, String payload, String errorMessage) {
        this.originalTopic = originalTopic;
        this.eventId = eventId;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.retryCount = 0;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }
}
