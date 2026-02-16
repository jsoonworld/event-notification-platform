package com.jsoonworld.notification.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("notification_log")
@Getter
@Setter
@NoArgsConstructor
public class NotificationLogEntity {

    @Id
    private Long id;

    @Column("notification_id")
    private String notificationId;

    @Column("event_id")
    private String eventId;

    @Column("event_type")
    private String eventType;

    @Column("user_id")
    private Long userId;

    @Column("channel")
    private String channel;

    @Column("status")
    private String status;

    @Column("recipient")
    private String recipient;

    @Column("error_message")
    private String errorMessage;

    @Column("sent_at")
    private Instant sentAt;

    @Column("created_at")
    private Instant createdAt;

    public NotificationLogEntity(String notificationId, String eventId, String eventType,
                                  Long userId, String channel, String status,
                                  String recipient, String errorMessage, Instant sentAt) {
        this.notificationId = notificationId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.userId = userId;
        this.channel = channel;
        this.status = status;
        this.recipient = recipient;
        this.errorMessage = errorMessage;
        this.sentAt = sentAt;
        this.createdAt = Instant.now();
    }
}
