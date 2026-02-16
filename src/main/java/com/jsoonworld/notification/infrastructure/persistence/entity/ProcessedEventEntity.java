package com.jsoonworld.notification.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("processed_events")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedEventEntity {

    @Id
    private Long id;

    @Column("event_id")
    private String eventId;

    @Column("consumer_group")
    private String consumerGroup;

    @Column("processed_at")
    private Instant processedAt;

    public ProcessedEventEntity(String eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
    }
}
