package com.tickefy.order.modules.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refund_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundJobEntity {

    @Id
    @Column(name = "concert_id", nullable = false, updatable = false)
    private UUID concertId;

    @Column(name = "enabled_at", nullable = false, updatable = false)
    private Instant enabledAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
