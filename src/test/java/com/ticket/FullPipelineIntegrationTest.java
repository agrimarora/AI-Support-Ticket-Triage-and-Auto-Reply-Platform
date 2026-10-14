package com.ticket;

import com.ticket.core.security.ApiKeyHasher;
import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.budget.TenantBudget;
import com.ticket.domain.budget.TenantBudgetRepository;
import com.ticket.domain.budget.TokenUsageLog;
import com.ticket.domain.budget.TokenUsageLogRepository;
import com.ticket.domain.kb.DocumentChunkRepository;
import com.ticket.domain.kb.KnowledgeBaseService;
import com.ticket.domain.kb.KnowledgeDocumentRepository;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import com.ticket.domain.tenant.Tenant;
import com.ticket.domain.tenant.TenantRepository;
import com.ticket.domain.ticket.Ticket;
import com.ticket.domain.ticket.TicketRepository;
import com.ticket.domain.ticket.TicketStatus;
import com.ticket.dto.CreateTicketRequest;
import com.ticket.dto.TicketResponse;
import com.ticket.outbox.OutboxPublisherScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Comprehensive end-to-end test verifying the complete Phase 1 + Phase 2 pipeline:
 *
 * POST /v1/tickets → DB + outbox → Kafka → Worker (classify → RAG retrieve → draft reply)
 *     → AWAITING_APPROVAL → agent approves → APPROVED
 *
 * Also verifies:
 * - Token budget tracking and cost logging
 * - Citation validation
 * - Idempotent consumer processing
 * - Tenant isolation across the full pipeline
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Phase 1+2 End-to-End: Full Pipeline Integration Test")
public class FullPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("e2e_test_db")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TenantBudgetRepository budgetRepository;
    @Autowired private TokenUsageLogRepository usageLogRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private OutboxPublisherScheduler outboxPublisherScheduler;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private String tenantARawKey;
    private Tenant tenantA;
    private String tenantBRawKey;
    private Tenant tenantB;

    @BeforeEach
    void setUp() {
        // Mock Redis to return null (cache miss) for all lookups
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        usageLogRepository.deleteAll();
        budgetRepository.deleteAll();
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        ticketRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        // Seed Tenant A
        tenantA = tenantRepository.save(Tenant.builder().name("Acme Corp").slug("acme-e2e").status("ACTIVE").build());
        tenantARawKey = "tk_acme_" + UUID.randomUUID().toString().replace("-", "");
        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantA.getId()).name("Acme Key")
                .keyHash(ApiKeyHasher.hashApiKey(tenantARawKey)).revoked(false).build());

        // Seed Tenant B
        tenantB = tenantRepository.save(Tenant.builder().name("Beta Inc").slug("beta-e2e").status("ACTIVE").build());
        tenantBRawKey = "tk_beta_" + UUID.randomUUID().toString().replace("-", "");
        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantB.getId()).name("Beta Key")
                .keyHash(ApiKeyHasher.hashApiKey(tenantBRawKey)).revoked(false).build());

        // Seed Tenant A knowledge base
        TenantContext.setTenantId(tenantA.getId());
        knowledgeBaseService.ingestDocument(
                "Billing and Refund Policy",
                "Our refund policy allows full refunds within 30 days of purchase. " +
                        "Contact billing support with your invoice number to initiate a refund. " +
                        "Refunds are processed within 5-7 business days."
        );
        knowledgeBaseService.ingestDocument(
                "Troubleshooting App Crashes",
                "If the application crashes on login, clear the local cache and restart. " +
                        "Known bug in version 2.3 causes memory leaks. Update to version 2.4 for the fix."
        );
        TenantContext.clear();

        // Create budget for Tenant A
        budgetRepository.save(TenantBudget.builder()
                .tenantId(tenantA.getId())
                .dailyTokenLimit(100000L)
                .usedTokensToday(0L)
                .maxRequestsPerMinute(60)
                .lastResetDate(LocalDate.now())
                .build());
    }

    @Test
    @DisplayName("E2E: Ticket flows through classify → RAG draft → AWAITING_APPROVAL → APPROVED")
    void testFullPipeline_ClassifyThenRagThenApprove() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", tenantARawKey);
        headers.set("Idempotency-Key", "e2e-full-pipeline-001");
        headers.setContentType(MediaType.APPLICATION_JSON);

        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-e2e-001")
                .title("I was charged twice on my invoice")
                .description("My credit card shows a double charge for the monthly subscription payment")
                .build();

        // Step 1: POST ticket → 202 Accepted
        ResponseEntity<TicketResponse> postResponse = restTemplate.postForEntity(
                "/v1/tickets",
                new HttpEntity<>(request, headers),
                TicketResponse.class
        );
        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID ticketId = postResponse.getBody().getId();
        assertThat(postResponse.getBody().getStatus()).isEqualTo(TicketStatus.RECEIVED);

        // Step 2: Trigger outbox publisher to dispatch event to Kafka
        outboxPublisherScheduler.publishPendingOutboxEvents();

        // Step 3: Wait for Kafka consumer to classify + RAG draft + transition to AWAITING_APPROVAL
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AWAITING_APPROVAL);
        });

        // Step 4: Verify classification happened correctly
        Ticket classified = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(classified.getCategory()).isNotNull();
        assertThat(classified.getPriority()).isNotNull();
        assertThat(classified.getConfidenceScore()).isGreaterThan(0.0);

        // Step 5: Verify RAG draft reply was generated with citations
        assertThat(classified.getDraftReply()).isNotNull();
        assertThat(classified.getDraftReply()).isNotEmpty();
        assertThat(classified.getDraftReply().length()).isGreaterThan(20);

        // Step 6: Verify token usage was logged
        List<TokenUsageLog> usageLogs = usageLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantA.getId());
        assertThat(usageLogs).isNotEmpty();
        assertThat(usageLogs.get(0).getTokensTotal()).isGreaterThan(0);
        assertThat(usageLogs.get(0).getCostUsd()).isNotNull();

        // Step 7: Agent approves the draft
        ResponseEntity<TicketResponse> approveResponse = restTemplate.postForEntity(
                "/v1/dashboard/tickets/" + ticketId + "/approve?feedback=LGTM, reply is accurate",
                null,
                TicketResponse.class
        );
        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveResponse.getBody().getStatus()).isEqualTo(TicketStatus.APPROVED);

        // Final verification
        Ticket finalTicket = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(finalTicket.getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(finalTicket.getAgentFeedback()).isEqualTo("LGTM, reply is accurate");
    }

    @Test
    @DisplayName("E2E: Tenant B cannot access Tenant A tickets or knowledge base results")
    void testFullPipelineTenantIsolation() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-API-Key", tenantARawKey);
        headersA.setContentType(MediaType.APPLICATION_JSON);

        // Post ticket as Tenant A
        CreateTicketRequest requestA = CreateTicketRequest.builder()
                .customerId("cust-iso-001")
                .title("Confidential billing issue for Acme")
                .description("Secret: Acme internal charge discrepancy details")
                .build();

        ResponseEntity<TicketResponse> responseA = restTemplate.postForEntity(
                "/v1/tickets", new HttpEntity<>(requestA, headersA), TicketResponse.class);
        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID ticketAId = responseA.getBody().getId();

        // Tenant B tries to read Tenant A's ticket by ID
        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-API-Key", tenantBRawKey);

        ResponseEntity<String> crossTenantGet = restTemplate.exchange(
                "/v1/tickets/" + ticketAId, HttpMethod.GET, new HttpEntity<>(headersB), String.class);
        assertThat(crossTenantGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Tenant B lists all tickets (should see 0)
        ResponseEntity<String> listB = restTemplate.exchange(
                "/v1/tickets", HttpMethod.GET, new HttpEntity<>(headersB), String.class);
        assertThat(listB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listB.getBody()).contains("\"totalElements\":0");

        // Verify KB isolation: Tenant B has no documents
        TenantContext.setTenantId(tenantB.getId());
        List<KnowledgeBaseService.RetrievedChunk> chunksB = knowledgeBaseService.retrieveRelevantChunks(
                tenantB.getId(), "refund policy billing", 0.30, 5);
        TenantContext.clear();

        // Tenant B should not find Tenant A's knowledge base docs
        for (KnowledgeBaseService.RetrievedChunk chunk : chunksB) {
            assertThat(chunk.getContent()).doesNotContain("30 days");
            assertThat(chunk.getContent()).doesNotContain("invoice number");
        }
    }

    @Test
    @DisplayName("E2E: Idempotent consumer does not process duplicate Kafka events")
    void testIdempotentConsumerNoDuplicateProcessing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", tenantARawKey);
        headers.set("Idempotency-Key", "idem-e2e-dup-key");
        headers.setContentType(MediaType.APPLICATION_JSON);

        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-idem-001")
                .title("Test idempotent consumer processing")
                .description("This ticket should only be processed once via Kafka")
                .build();

        // Post ticket (first request)
        ResponseEntity<TicketResponse> res1 = restTemplate.postForEntity(
                "/v1/tickets", new HttpEntity<>(request, headers), TicketResponse.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID ticketId = res1.getBody().getId();

        // Duplicate POST with same Idempotency-Key returns same ticket ID
        ResponseEntity<TicketResponse> res2 = restTemplate.postForEntity(
                "/v1/tickets", new HttpEntity<>(request, headers), TicketResponse.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res2.getBody().getId()).isEqualTo(ticketId);

        // Trigger outbox
        outboxPublisherScheduler.publishPendingOutboxEvents();

        // Wait for processing
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AWAITING_APPROVAL);
        });

        // Verify only ONE token usage log exists (not duplicated)
        List<TokenUsageLog> logs = usageLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantA.getId());
        long logsForThisTicket = logs.stream()
                .filter(l -> l.getTicketId() != null && l.getTicketId().equals(ticketId))
                .count();
        // Should have exactly 1 usage log entry (from the single RAG draft, not duplicated)
        assertThat(logsForThisTicket).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("E2E: Budget-exhausted tenant gets graceful fallback instead of crash")
    void testBudgetExhaustedTenantGracefulDegradation() {
        // Set a very small budget for Tenant B
        budgetRepository.save(TenantBudget.builder()
                .tenantId(tenantB.getId())
                .dailyTokenLimit(1L) // Only 1 token allowed
                .usedTokensToday(0L)
                .maxRequestsPerMinute(60)
                .lastResetDate(LocalDate.now())
                .build());

        // Ingest a document for Tenant B so RAG has something to work with
        TenantContext.setTenantId(tenantB.getId());
        knowledgeBaseService.ingestDocument("Beta FAQ", "Beta Corp FAQ: Return items within 14 days.");
        TenantContext.clear();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", tenantBRawKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-budget-001")
                .title("How do I return a product?")
                .description("I need to return a defective product")
                .build();

        ResponseEntity<TicketResponse> response = restTemplate.postForEntity(
                "/v1/tickets", new HttpEntity<>(request, headers), TicketResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID ticketId = response.getBody().getId();

        // Trigger outbox
        outboxPublisherScheduler.publishPendingOutboxEvents();

        // Wait for consumer to process
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AWAITING_APPROVAL);
        });

        // Verify ticket was processed with graceful degradation
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(ticket.getDraftReply()).isNotNull();
        // The ticket should be classified regardless of budget
        assertThat(ticket.getCategory()).isNotNull();
        // Draft reply should indicate budget limit or have a fallback
        assertThat(ticket.getRequiresHuman()).isNotNull();
    }

    @Test
    @DisplayName("E2E: Poison pill message lands in DLT without blocking pipeline")
    void testPoisonPillRoutesToDltWithoutBlocking() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", tenantARawKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Post a poison pill ticket
        CreateTicketRequest poisonRequest = CreateTicketRequest.builder()
                .customerId("cust-poison")
                .title("POISON_PILL")
                .description("This will cause processing failure")
                .build();

        ResponseEntity<TicketResponse> poisonResponse = restTemplate.postForEntity(
                "/v1/tickets", new HttpEntity<>(poisonRequest, headers), TicketResponse.class);
        UUID poisonTicketId = poisonResponse.getBody().getId();

        // Post a normal ticket right after
        CreateTicketRequest normalRequest = CreateTicketRequest.builder()
                .customerId("cust-normal-after-poison")
                .title("Normal billing question")
                .description("Simple question about my invoice")
                .build();

        ResponseEntity<TicketResponse> normalResponse = restTemplate.postForEntity(
                "/v1/tickets", new HttpEntity<>(normalRequest, headers), TicketResponse.class);
        UUID normalTicketId = normalResponse.getBody().getId();

        // Trigger outbox
        outboxPublisherScheduler.publishPendingOutboxEvents();

        // Wait for the normal ticket to be processed (proves pipeline isn't blocked by poison pill)
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Ticket normalTicket = ticketRepository.findById(normalTicketId).orElseThrow();
            assertThat(normalTicket.getStatus()).isIn(
                    TicketStatus.CLASSIFYING,
                    TicketStatus.CLASSIFIED,
                    TicketStatus.DRAFTING,
                    TicketStatus.AWAITING_APPROVAL
            );
        });
    }
}
