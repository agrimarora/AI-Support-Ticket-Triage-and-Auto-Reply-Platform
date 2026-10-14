package com.ticket.domain.budget;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "token_usage_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(nullable = false, length = 50)
    private String operation;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "tokens_prompt", nullable = false)
    private int tokensPrompt;

    @Column(name = "tokens_completion", nullable = false)
    private int tokensCompletion;

    @Column(name = "tokens_total", nullable = false)
    private int tokensTotal;

    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
