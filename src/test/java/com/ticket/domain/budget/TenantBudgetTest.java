package com.ticket.domain.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class TenantBudgetTest {

    @Test
    @DisplayName("Budget: Correctly tracks token limits and prevents exceeding daily quota")
    void testTenantBudgetConsumption() {
        TenantBudget budget = TenantBudget.builder()
                .tenantId(UUID.randomUUID())
                .dailyTokenLimit(1000)
                .usedTokensToday(800)
                .maxRequestsPerMinute(60)
                .lastResetDate(LocalDate.now())
                .build();

        assertThat(budget.canConsumeTokens(150)).isTrue();
        assertThat(budget.canConsumeTokens(250)).isFalse();

        budget.recordTokens(150);
        assertThat(budget.getUsedTokensToday()).isEqualTo(950);
        assertThat(budget.canConsumeTokens(100)).isFalse();
    }
}
