package com.jsoonworld.notification.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Table("delivery_stats")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryStatsEntity {

    @Id
    private Long id;

    @Column("date")
    private LocalDate date;

    @Column("channel")
    private String channel;

    @Column("sent_count")
    private Integer sentCount;

    @Column("failed_count")
    private Integer failedCount;

    @Column("success_rate")
    private BigDecimal successRate;

    public DeliveryStatsEntity(LocalDate date, String channel) {
        this.date = date;
        this.channel = channel;
        this.sentCount = 0;
        this.failedCount = 0;
    }
}
