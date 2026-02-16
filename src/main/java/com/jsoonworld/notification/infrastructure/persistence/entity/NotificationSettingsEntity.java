package com.jsoonworld.notification.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("notification_settings")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSettingsEntity {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("email_enabled")
    private Boolean emailEnabled;

    @Column("slack_enabled")
    private Boolean slackEnabled;

    @Column("push_enabled")
    private Boolean pushEnabled;

    @Column("in_app_enabled")
    private Boolean inAppEnabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public NotificationSettingsEntity(Long userId, boolean emailEnabled, boolean slackEnabled,
                                       boolean pushEnabled, boolean inAppEnabled) {
        this.userId = userId;
        this.emailEnabled = emailEnabled;
        this.slackEnabled = slackEnabled;
        this.pushEnabled = pushEnabled;
        this.inAppEnabled = inAppEnabled;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
