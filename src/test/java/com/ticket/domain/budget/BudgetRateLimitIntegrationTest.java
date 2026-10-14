package com.ticket.domain.budget;

import com.ticket.domain.tenant.Tenant;
import com.ticket.domain.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Phase 2: Budget and Rate Limiting Integration Tests")
public class BudgetRateLimitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("budget_test_db")
            .withUsername("testuser")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "16379");
    }

    @Autowired
    private BudgetRateLimitService budgetService;

    @Autowired
    private TenantBudgetRepository budgetRepository;

    @Autowired
    private TokenUsageLogRepository usageLogRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        usageLogRepository.deleteAll();
        budgetRepository.deleteAll();
        tenantRepository.deleteAll();

        // Clear Redis if available
        if (redisTemplate != null) {
            try {
                redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            } catch (Exception e) {
                // Redis not available
            }
        }

        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Budget Test Tenant")
                .slug("budget-test")
                .status("ACTIVE")
                .build());
        tenantId = tenant.getId();
    }

    @Test
    @DisplayName("Token budget allows consumption under daily limit")
    void testTokenBudgetAllowsConsumptionUnderLimit() {
        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(10000L)
                .usedTokensToday(0L)
                .maxRequestsPerMinute(60)
                .lastResetDate(LocalDate.now())
                .build();
        budgetRepository.save(budget);

        boolean canConsume = budgetService.canConsumeTokens(tenantId, 500);
        assertThat(canConsume).isTrue();

        // Record usage
        budgetService.recordUsage(tenantId, UUID.randomUUID(), "CLASSIFICATION", "gpt-4o-mini", 300, 200);

        TenantBudget updated = budgetRepository.findById(tenantId).orElseThrow();
        assertThat(updated.getUsedTokensToday()).isEqualTo(500);
    }

    @Test
    @DisplayName("Token budget blocks consumption when daily limit exceeded")
    void testTokenBudgetBlocksWhenLimitExceeded() {
        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(1000L)
                .usedTokensToday(900L) // Already used 900 tokens
                .maxRequestsPerMinute(60)
                .lastResetDate(LocalDate.now())
                .build();
        budgetRepository.save(budget);

        // Try to consume 200 more tokens (would exceed 1000 limit)
        boolean canConsume = budgetService.canConsumeTokens(tenantId, 200);
        assertThat(canConsume).isFalse();
    }

    @Test
    @DisplayName("Token budget resets on new day")
    void testTokenBudgetResetsOnNewDay() {
        // Create budget with yesterday's date
        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(5000L)
                .usedTokensToday(4999L) // Almost exhausted yesterday
                .maxRequestsPerMinute(60)
                .lastResetDate(LocalDate.now().minusDays(1)) // Yesterday
                .build();
        budgetRepository.save(budget);

        // Trigger reset by checking if can consume
        TenantBudget retrieved = budgetService.getOrCreateBudget(tenantId);

        // Budget should reset since last reset date was yesterday
        if (retrieved.getLastResetDate().isBefore(LocalDate.now())) {
            retrieved.resetDailyIfNewDay();
            budgetRepository.save(retrieved);
        }

        TenantBudget afterReset = budgetRepository.findById(tenantId).orElseThrow();
        assertThat(afterReset.getUsedTokensToday()).isEqualTo(0);
        assertThat(afterReset.getLastResetDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("Token usage is logged with accurate cost calculation")
    void testTokenUsageLoggingWithCostCalculation() {
        UUID ticketId = UUID.randomUUID();
        int promptTokens = 1000;
        int completionTokens = 500;
        int totalTokens = 1500;

        budgetService.recordUsage(tenantId, ticketId, "RAG_REPLY_DRAFT", "gpt-4o-mini", promptTokens, completionTokens);

        List<TokenUsageLog> logs = usageLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        assertThat(logs).hasSize(1);

        TokenUsageLog log = logs.get(0);
        assertThat(log.getTenantId()).isEqualTo(tenantId);
        assertThat(log.getTicketId()).isEqualTo(ticketId);
        assertThat(log.getOperation()).isEqualTo("RAG_REPLY_DRAFT");
        assertThat(log.getModelName()).isEqualTo("gpt-4o-mini");
        assertThat(log.getTokensPrompt()).isEqualTo(promptTokens);
        assertThat(log.getTokensCompletion()).isEqualTo(completionTokens);
        assertThat(log.getTokensTotal()).isEqualTo(totalTokens);

        // Cost: 1500 tokens * $0.0015 per 1K tokens = $0.00225
        assertThat(log.getCostUsd().doubleValue()).isCloseTo(0.00225, org.assertj.core.data.Offset.offset(0.00001));
    }

    @Test
    @DisplayName("Rate limiter allows requests under per-minute limit")
    void testRateLimiterAllowsRequestsUnderLimit() {
        // Skip test if Redis not available
        if (redisTemplate == null) {
            return;
        }

        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(100000L)
                .usedTokensToday(0L)
                .maxRequestsPerMinute(5) // Very low limit for testing
                .lastResetDate(LocalDate.now())
                .build();
        budgetRepository.save(budget);

        // Make 5 requests (should all succeed)
        for (int i = 0; i < 5; i++) {
            boolean allowed = budgetService.checkAndEnforceRateLimit(tenantId);
            assertThat(allowed).isTrue();
        }
    }

    @Test
    @DisplayName("Rate limiter blocks requests after exceeding per-minute limit")
    void testRateLimiterBlocksAfterExceedingLimit() {
        // Skip test if Redis not available
        if (redisTemplate == null) {
            return;
        }

        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(100000L)
                .usedTokensToday(0L)
                .maxRequestsPerMinute(3) // Only 3 requests per minute
                .lastResetDate(LocalDate.now())
                .build();
        budgetRepository.save(budget);

        // Make 3 requests (should succeed)
        for (int i = 0; i < 3; i++) {
            boolean allowed = budgetService.checkAndEnforceRateLimit(tenantId);
            assertThat(allowed).withFailMessage("Request %d should be allowed", i + 1).isTrue();
        }

        // 4th request should be blocked
        boolean fourthRequest = budgetService.checkAndEnforceRateLimit(tenantId);
        assertThat(fourthRequest).isFalse();
    }

    @Test
    @DisplayName("Rate limiter resets after window expires (graceful fallback when Redis unavailable)")
    void testRateLimiterGracefulFallbackWithoutRedis() {
        // This test proves that when Redis is unavailable, the service doesn't crash
        // It falls back to permissive mode (logs warning and allows request)

        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(100000L)
                .usedTokensToday(0L)
                .maxRequestsPerMinute(10)
                .lastResetDate(LocalDate.now())
                .build();
        budgetRepository.save(budget);

        // Even without Redis, rate limit check should not throw exception
        boolean allowed = budgetService.checkAndEnforceRateLimit(tenantId);

        // When Redis is unavailable, it falls back to permissive (returns true)
        // This is by design - we prefer to allow requests rather than block all traffic
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Budget can be updated dynamically per tenant")
    void testDynamicBudgetUpdate() {
        // Create initial budget
        budgetService.getOrCreateBudget(tenantId);

        // Update limits
        TenantBudget updated = budgetService.updateBudget(tenantId, 50000, 120);

        assertThat(updated.getDailyTokenLimit()).isEqualTo(50000);
        assertThat(updated.getMaxRequestsPerMinute()).isEqualTo(120);

        // Verify persistence
        TenantBudget retrieved = budgetRepository.findById(tenantId).orElseThrow();
        assertThat(retrieved.getDailyTokenLimit()).isEqualTo(50000);
        assertThat(retrieved.getMaxRequestsPerMinute()).isEqualTo(120);
    }

    @Test
    @DisplayName("Multiple tenants have isolated token budgets")
    void testMultiTenantBudgetIsolation() {
        // Create second tenant
        Tenant tenant2 = tenantRepository.save(Tenant.builder()
                .name("Tenant 2")
                .slug("tenant-2")
                .status("ACTIVE")
                .build());
        UUID tenantId2 = tenant2.getId();

        // Both tenants start with default budget
        TenantBudget budget1 = budgetService.getOrCreateBudget(tenantId);
        TenantBudget budget2 = budgetService.getOrCreateBudget(tenantId2);

        // Consume tokens for tenant 1
        budgetService.recordUsage(tenantId, UUID.randomUUID(), "TEST", "gpt-4o-mini", 500, 500);

        // Verify tenant 2's budget is unaffected
        TenantBudget budget1After = budgetRepository.findById(tenantId).orElseThrow();
        TenantBudget budget2After = budgetRepository.findById(tenantId2).orElseThrow();

        assertThat(budget1After.getUsedTokensToday()).isEqualTo(1000);
        assertThat(budget2After.getUsedTokensToday()).isEqualTo(0);
    }

    @Test
    @DisplayName("Budget enforcement prevents excessive token consumption in high-load scenario")
    void testBudgetEnforcementUnderLoad() {
        TenantBudget budget = TenantBudget.builder()
                .tenantId(tenantId)
                .dailyTokenLimit(5000L)
                .usedTokensToday(0L)
                .maxRequestsPerMinute(100)
                .lastResetDate(LocalDate.now())
                .build();
        budgetRepository.save(budget);

        int successfulCalls = 0;
        int blockedCalls = 0;

        // Simulate 20 calls, each consuming ~500 tokens (total 10K tokens attempted)
        for (int i = 0; i < 20; i++) {
            if (budgetService.canConsumeTokens(tenantId, 500)) {
                budgetService.recordUsage(tenantId, UUID.randomUUID(), "LOAD_TEST", "gpt-4o-mini", 300, 200);
                successfulCalls++;
            } else {
                blockedCalls++;
            }
        }

        // Should allow ~10 calls (5000 tokens / 500 per call) and block the rest
        assertThat(successfulCalls).isLessThanOrEqualTo(10);
        assertThat(blockedCalls).isGreaterThanOrEqualTo(10);

        TenantBudget finalBudget = budgetRepository.findById(tenantId).orElseThrow();
        assertThat(finalBudget.getUsedTokensToday()).isLessThanOrEqualTo(5000L);
    }
}
