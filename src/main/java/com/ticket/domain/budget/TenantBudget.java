package com.ticket.domain.budget;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "tenant_budgets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantBudget {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "daily_token_limit", nullable = false)
    private long dailyTokenLimit;

    @Column(name = "used_tokens_today", nullable = false)
    private long usedTokensToday;

    @Column(name = "max_requests_per_minute", nullable = false)
    private int maxRequestsPerMinute;

    @Column(name = "last_reset_date", nullable = false)
    private LocalDate lastResetDate;

    public void resetDailyIfNewDay() {
        LocalDate today = LocalDate.now();
        if (lastResetDate == null || today.isAfter(lastResetDate)) {
            this.usedTokensToday = 0;
            this.lastResetDate = today;
        }
    }

    public boolean canConsumeTokens(long tokens) {
        resetDailyIfNewDay();
        return (this.usedTokensToday + tokens) <= this.dailyTokenLimit;
    }

    public void recordTokens(long tokens) {
        resetDailyIfNewDay();
        this.usedTokensToday += tokens;
    }
}
