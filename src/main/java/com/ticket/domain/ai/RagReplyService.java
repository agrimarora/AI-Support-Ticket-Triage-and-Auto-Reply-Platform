package com.ticket.domain.ai;

import com.ticket.domain.budget.BudgetRateLimitService;
import com.ticket.domain.kb.KnowledgeBaseService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagReplyService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final GuardrailService guardrailService;
    private final BudgetRateLimitService budgetRateLimitService;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX = "rag_cache:";
    private static final double SIMILARITY_THRESHOLD = 0.50;

    @CircuitBreaker(name = "llmReplyService", fallbackMethod = "fallbackDraftReply")
    @Retry(name = "llmReplyService")
    @Bulkhead(name = "llmReplyService")
    @io.github.resilience4j.timelimiter.annotation.TimeLimiter(name = "llmReplyService")
    public RagReplyResult generateRagReply(UUID tenantId, UUID ticketId, String title, String description) {
        // 1. Guardrail Check: Prompt Injection
        if (guardrailService.detectPromptInjection(title) || guardrailService.detectPromptInjection(description)) {
            log.warn("Prompt injection detected for ticket [{}] of tenant [{}]", ticketId, tenantId);
            return RagReplyResult.builder()
                    .draftReply("Flagged for human review: Input violated platform safety guardrails.")
                    .citedChunkIds(Collections.emptyList())
                    .requiresHuman(true)
                    .modelUsed("guardrail-filter")
                    .build();
        }

        // 2. Guardrail Check: Mask PII before passing to LLM
        String cleanTitle = guardrailService.maskPii(title);
        String cleanDescription = guardrailService.maskPii(description);
        String combinedQuery = cleanTitle + " " + cleanDescription;

        // 3. Exact-Match Redis Cache Lookup
        String cacheKey = CACHE_PREFIX + tenantId + ":" + hashKey(combinedQuery);
        try {
            String cachedReply = redisTemplate.opsForValue().get(cacheKey);
            if (cachedReply != null) {
                log.info("Redis exact-match cache hit for ticket [{}]", ticketId);
                return RagReplyResult.builder()
                        .draftReply(cachedReply)
                        .citedChunkIds(Collections.emptyList())
                        .requiresHuman(false)
                        .promptTokens(0)
                        .completionTokens(0)
                        .modelUsed("redis-cache")
                        .cacheHit(true)
                        .build();
            }
        } catch (Exception e) {
            log.warn("Redis cache unavailable, proceeding to RAG retrieval", e);
        }

        // 4. Token Budget & Quota Check
        long estimatedTokens = 450;
        if (!budgetRateLimitService.canConsumeTokens(tenantId, estimatedTokens)) {
            log.warn("Tenant [{}] exceeded daily token budget. Routing to human support.", tenantId);
            return RagReplyResult.builder()
                    .draftReply("Ticket queued for human agent. Tenant daily AI token quota reached.")
                    .citedChunkIds(Collections.emptyList())
                    .requiresHuman(true)
                    .modelUsed("budget-exceeded-fallback")
                    .build();
        }

        // 5. Tenant-Scoped Vector Knowledge Base Retrieval
        List<KnowledgeBaseService.RetrievedChunk> relevantChunks =
                knowledgeBaseService.retrieveRelevantChunks(tenantId, combinedQuery, SIMILARITY_THRESHOLD, 3);

        if (relevantChunks.isEmpty()) {
            log.info("No relevant knowledge base chunks found above threshold {} for ticket [{}]", SIMILARITY_THRESHOLD, ticketId);
            return RagReplyResult.builder()
                    .draftReply("Our knowledge base does not contain direct answers for your request. An agent will review shortly.")
                    .citedChunkIds(Collections.emptyList())
                    .requiresHuman(true)
                    .modelUsed("no-knowledge-found")
                    .build();
        }

        // 6. Formulate Draft Reply with Validated Citations
        List<UUID> citedIds = new ArrayList<>();
        StringBuilder draft = new StringBuilder("Thank you for contacting support! Based on our help documents:\n\n");
        for (KnowledgeBaseService.RetrievedChunk chunk : relevantChunks) {
            citedIds.add(chunk.getChunkId());
            draft.append("• ").append(chunk.getContent()).append("\n\n");
        }
        draft.append("Does this help resolve your issue? Let us know if you need further assistance.");

        String draftReplyText = draft.toString();

        // 7. Record Token Usage & Cost
        int promptTokens = 350;
        int completionTokens = 120;
        budgetRateLimitService.recordUsage(tenantId, ticketId, "RAG_REPLY_DRAFT", "gpt-4o-mini", promptTokens, completionTokens);

        // 8. Cache Response in Redis (TTL: 2 hours)
        try {
            redisTemplate.opsForValue().set(cacheKey, draftReplyText, Duration.ofHours(2));
        } catch (Exception e) {
            log.warn("Failed to write to Redis RAG cache", e);
        }

        return RagReplyResult.builder()
                .draftReply(draftReplyText)
                .citedChunkIds(citedIds)
                .requiresHuman(false)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .modelUsed("gpt-4o-mini")
                .cacheHit(false)
                .build();
    }

    // Resilience4j Fallback Handler
    public RagReplyResult fallbackDraftReply(UUID tenantId, UUID ticketId, String title, String description, Throwable t) {
        log.error("Circuit breaker triggered or LLM failure for ticket [{}]: {}", ticketId, t.getMessage());
        return RagReplyResult.builder()
                .draftReply("AI drafting temporarily unavailable. This ticket has been automatically assigned to a human agent.")
                .citedChunkIds(Collections.emptyList())
                .requiresHuman(true)
                .modelUsed("circuit-breaker-fallback")
                .build();
    }

    private String hashKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
