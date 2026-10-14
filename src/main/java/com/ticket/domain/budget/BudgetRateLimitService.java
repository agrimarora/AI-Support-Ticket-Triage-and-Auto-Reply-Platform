package com.ticket.domain.budget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetRateLimitService {

    private final TenantBudgetRepository budgetRepository;
    private final TokenUsageLogRepository usageLogRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    private static final BigDecimal COST_PER_1K_TOKENS = new BigDecimal("0.001500");

    @Transactional
    public boolean checkAndEnforceRateLimit(UUID tenantId) {
        TenantBudget budget = getOrCreateBudget(tenantId);
        String redisKey = RATE_LIMIT_PREFIX + tenantId + ":" + (System.currentTimeMillis() / 60000);

        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1) {
                redisTemplate.expire(redisKey, Duration.ofMinutes(2));
            }
            if (count != null && count > budget.getMaxRequestsPerMinute()) {
                log.warn("Tenant [{}] exceeded request rate limit: {}/min", tenantId, count);
                return false;
            }
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable, falling back to permissive", e);
        }

        return true;
    }

    @Transactional
    public boolean canConsumeTokens(UUID tenantId, long estimatedTokens) {
        TenantBudget budget = getOrCreateBudget(tenantId);
        return budget.canConsumeTokens(estimatedTokens);
    }

    @Transactional
    public void recordUsage(UUID tenantId, UUID ticketId, String operation, String model, int promptTokens, int completionTokens) {
        int total = promptTokens + completionTokens;
        TenantBudget budget = getOrCreateBudget(tenantId);
        budget.recordTokens(total);
        budgetRepository.save(budget);

        BigDecimal cost = COST_PER_1K_TOKENS.multiply(BigDecimal.valueOf(total))
                .divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP);

        TokenUsageLog usageLog = TokenUsageLog.builder()
                .tenantId(tenantId)
                .ticketId(ticketId)
                .operation(operation)
                .modelName(model)
                .tokensPrompt(promptTokens)
                .tokensCompletion(completionTokens)
                .tokensTotal(total)
                .costUsd(cost)
                .build();

        usageLogRepository.save(usageLog);
        log.info("Recorded token usage for tenant [{}]: {} tokens (${})", tenantId, total, cost);
    }

    @Transactional
    public TenantBudget getOrCreateBudget(UUID tenantId) {
        return budgetRepository.findById(tenantId).orElseGet(() -> {
            TenantBudget newBudget = TenantBudget.builder()
                    .tenantId(tenantId)
                    .dailyTokenLimit(100000)
                    .usedTokensToday(0)
                    .maxRequestsPerMinute(60)
                    .lastResetDate(LocalDate.now())
                    .build();
            return budgetRepository.save(newBudget);
        });
    }

    @Transactional
    public TenantBudget updateBudget(UUID tenantId, Integer dailyTokenLimit, Integer rateLimitPerMinute) {
        TenantBudget budget = getOrCreateBudget(tenantId);
        if (dailyTokenLimit != null) {
            budget.setDailyTokenLimit(dailyTokenLimit);
        }
        if (rateLimitPerMinute != null) {
            budget.setMaxRequestsPerMinute(rateLimitPerMinute);
        }
        return budgetRepository.save(budget);
    }
}
